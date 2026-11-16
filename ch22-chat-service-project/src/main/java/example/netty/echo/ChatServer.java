package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

// 전송 계층과 서비스 계층을 조립하고 지표·정상 종료를 연결합니다.
public final class ChatServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ChatServer.class.getName());
    private final ChatSettings settings;
    private final SessionStore sessions;
    // 채팅방 상태를 다루는 단일 스레드 실행기입니다. 채널 이벤트 루프와 분리합니다.
    private final DefaultEventExecutor serviceExecutor =
            new DefaultEventExecutor(new DefaultThreadFactory("chat-service"));
    private final ChatService service;
    private final EventLoopGroup acceptor = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final EventLoopGroup workers = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
    private boolean closed;
    private Channel server;
    private ScheduledFuture<?> metrics;

    ChatServer(ChatSettings settings) {
        this.settings = settings;
        this.sessions = new SessionStore(settings.password(), settings.sessionMillis());
        this.service = new ChatService(serviceExecutor, settings);
    }

    private void start() throws Exception {
        SslContext tls = settings.tls() ? TlsContexts.server(false) : null;
        byte[] page = loadPage();
        // 명령 머리말(SAY, 메시지 ID)까지 담을 수 있도록 본문 한도에 여유를 둡니다.
        int frameLimit = settings.maxMessageBytes() + 128;
        ServerBootstrap bootstrap = new ServerBootstrap().group(acceptor, workers)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (tls != null) {
                            SslHandler ssl = tls.newHandler(ch.alloc());
                            ssl.setHandshakeTimeoutMillis(5000);
                            ch.pipeline().addLast(ssl);
                        }
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(8192));
                        ch.pipeline().addLast(new IdleStateHandler(30, 10, 0));
                        ch.pipeline().addLast(new ChatUpgradeGate(page, settings, sessions, service, channels));
                        ch.pipeline().addLast(new WebSocketServerProtocolHandler(
                                WebSocketServerProtocolConfig.newBuilder()
                                        .websocketPath("/ws")
                                        .checkStartsWith(false)
                                        .allowExtensions(false)
                                        .maxFramePayloadLength(frameLimit)
                                        .handshakeTimeoutMillis(5000)
                                        .forceCloseTimeoutMillis(3000)
                                        .build()));
                        ch.pipeline().addLast(new WebSocketFrameAggregator(frameLimit));
                        ch.pipeline().addLast(new ChatFrameHandler(service, settings, channels));
                    }
                });
        ChannelFuture bind = bootstrap.bind(settings.host(), settings.port());
        server = bind.channel();
        bind.sync();
        // 서비스 실행기에서 실행하므로 서비스 상태를 잠금 없이 읽을 수 있습니다.
        metrics = serviceExecutor.scheduleAtFixedRate(() -> {
            sessions.purgeExpired();
            LOG.log(System.Logger.Level.INFO, service.metricsLine() + " connections=" + channels.size()
                    + " sessions=" + sessions.size());
        }, 10, 10, TimeUnit.SECONDS);
        LOG.log(System.Logger.Level.INFO, "event=started " + settings);
    }

    private static byte[] loadPage() throws Exception {
        try (InputStream input = ChatServer.class.getResourceAsStream("/chat.html")) {
            if (input == null) throw new IllegalStateException("chat.html 리소스가 필요합니다.");
            byte[] page = input.readNBytes(64 * 1024 + 1);
            if (page.length > 64 * 1024) throw new IllegalArgumentException("페이지 크기 초과");
            return page;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        // 1. 새 명령과 새 업그레이드를 막고 접속 중인 사용자에게 종료를 알립니다.
        service.startDraining();
        LOG.log(System.Logger.Level.INFO, "event=drain_started connections=" + channels.size());
        // 2. 새 TCP 연결을 막습니다.
        if (server != null) server.close().syncUninterruptibly();
        // 3. 이미 접수한 명령이 서비스 실행기에서 모두 실행될 때까지 기다립니다.
        boolean idle;
        try {
            idle = service.awaitIdle(settings.drainMillis());
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            idle = false;
        }
        LOG.log(System.Logger.Level.INFO, "event=drain_finished idle=" + idle);
        // 4. 1001(Going Away)을 보내고 쓰기가 끝나면 상대의 Close 응답을 기다리지 않고 닫습니다.
        for (Channel channel : channels) WebSocketConnection.closeInLoop(channel, 1001, "server shutdown");
        channels.newCloseFuture().awaitUninterruptibly(1, TimeUnit.SECONDS);
        // 쓰기가 끝나지 않은 느린 연결까지 포함해 남은 채널을 닫습니다.
        channels.close().awaitUninterruptibly(3, TimeUnit.SECONDS);
        if (metrics != null) metrics.cancel(false);
        // 5. 채널 이벤트 루프를 먼저 끝내고, 그동안 넘어온 연결 정리 작업까지 실행한 뒤 서비스 실행기를 끝냅니다.
        workers.shutdownGracefully(0, 3, TimeUnit.SECONDS);
        acceptor.shutdownGracefully(0, 3, TimeUnit.SECONDS);
        workers.terminationFuture().syncUninterruptibly();
        acceptor.terminationFuture().syncUninterruptibly();
        serviceExecutor.shutdownGracefully(100, 3000, TimeUnit.MILLISECONDS).syncUninterruptibly();
        LOG.log(System.Logger.Level.INFO, "event=stopped");
    }

    public static void main(String[] args) throws Exception {
        ChatServer app = new ChatServer(ChatSettings.fromEnvironment());
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "chat-shutdown"));
        try {
            app.start();
            String scheme = app.settings.tls() ? "https" : "http";
            System.out.println(scheme + "://localhost:" + app.settings.port()
                    + "/ 에 접속하십시오. Enter 또는 정상 종료 신호로 종료합니다.");
            System.in.read();
        } finally {
            app.close();
        }
    }
}
