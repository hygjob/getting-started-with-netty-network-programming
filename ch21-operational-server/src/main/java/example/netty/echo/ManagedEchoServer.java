package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

// 로그·지표·접수 제한·정상 종료를 한 서버에 연결합니다.
public final class ManagedEchoServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ManagedEchoServer.class.getName());
    private final ServerSettings settings;
    private final EventLoopGroup acceptor = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final EventLoopGroup workers = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
    private final ChannelGroup clients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
    private final AtomicInteger connections = new AtomicInteger();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder writesSucceeded = new LongAdder();
    private final Object admission = new Object();
    private boolean draining;
    private int inFlight;
    private boolean closed;
    private Channel server;
    private ScheduledFuture<?> metrics;

    ManagedEchoServer(ServerSettings settings) { this.settings = settings; }

    private void start() throws Exception {
        ServerBootstrap bootstrap = new ServerBootstrap().group(acceptor, workers)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        WireProtocol.install(ch.pipeline());
                        ch.pipeline().addLast(new Handler());
                    }
                });
        ChannelFuture bind = bootstrap.bind(settings.host(), settings.port());
        server = bind.channel();
        bind.sync();
        // accepted와 rejected는 누적값이며 초당 처리량이 아닙니다.
        metrics = workers.next().scheduleAtFixedRate(() -> LOG.log(System.Logger.Level.INFO,
                "event=metrics connections=" + connections.get() + " in_flight=" + pending()
                        + " accepted=" + accepted.sum() + " rejected=" + rejected.sum()
                        + " writes_succeeded=" + writesSucceeded.sum()), 5, 5, TimeUnit.SECONDS);
        LOG.log(System.Logger.Level.INFO, "event=started host=" + settings.host()
                + " port=" + settings.port() + " drain_ms=" + settings.drainMillis());
    }

    private boolean begin() {
        synchronized (admission) {
            // 종료 시작과 접수가 경쟁해도 카운트되지 않은 새 업무가 드레인을 빠져나가지 않게 합니다.
            if (draining || inFlight >= settings.maxInFlight()) {
                rejected.increment();
                return false;
            }
            inFlight++;
            accepted.increment();
            return true;
        }
    }

    private void finish() {
        synchronized (admission) {
            inFlight--;
            admission.notifyAll();
        }
    }

    private int pending() { synchronized (admission) { return inFlight; } }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        // 서버 채널만 닫으면 기존 연결의 새 요청이 남으므로 접수도 먼저 막습니다.
        synchronized (admission) { draining = true; }
        LOG.log(System.Logger.Level.INFO, "event=drain_started in_flight=" + pending());
        if (server != null) server.close().syncUninterruptibly();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.drainMillis());
        synchronized (admission) {
            while (inFlight > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                try {
                    TimeUnit.NANOSECONDS.timedWait(admission, left);
                } catch (InterruptedException cause) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        LOG.log(System.Logger.Level.INFO, "event=drain_finished remaining=" + pending());
        clients.close().awaitUninterruptibly();
        if (metrics != null) metrics.cancel(false);
        // 업무를 먼저 정리한 뒤 quietPeriod 0으로 실행기 종료를 요청합니다.
        workers.shutdownGracefully(0, 3, TimeUnit.SECONDS);
        acceptor.shutdownGracefully(0, 3, TimeUnit.SECONDS);
        workers.terminationFuture().syncUninterruptibly();
        acceptor.terminationFuture().syncUninterruptibly();
        LOG.log(System.Logger.Level.INFO, "event=stopped");
    }

    private final class Handler extends SimpleChannelInboundHandler<WireMessage> {
        private final Set<Work> work = new HashSet<>();
        private boolean counted;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            if (connections.incrementAndGet() > settings.maxConnections()) {
                connections.decrementAndGet();
                ctx.close();
                return;
            }
            counted = true;
            clients.add(ctx.channel());
            ctx.fireChannelActive();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WireMessage msg) {
            if (!ctx.channel().isActive()) return;
            if (msg.type() != WireMessage.REQUEST || !ctx.channel().isWritable()) {
                ctx.close();
                return;
            }
            if (!begin()) {
                ctx.writeAndFlush(new WireMessage(WireMessage.ERROR, msg.requestId(), "unavailable"))
                        .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                return;
            }
            Work item = new Work();
            work.add(item);
            try {
                item.timer = ctx.executor().schedule(() -> {
                    if (!ctx.channel().isActive()) { item.done(); return; }
                    ctx.writeAndFlush(new WireMessage(WireMessage.RESPONSE, msg.requestId(), msg.body()))
                            .addListener(f -> {
                                if (f.isSuccess()) writesSucceeded.increment();
                                // 로컬 쓰기 결과이며 상대가 업무를 화면에 표시했다는 뜻은 아닙니다.
                                LOG.log(System.Logger.Level.INFO,
                                        "event=response_write channel=" + ctx.channel().id().asShortText()
                                                + " remote=" + ctx.channel().remoteAddress()
                                                + " request_id=" + msg.requestId()
                                                + " success=" + f.isSuccess());
                                item.done();
                                if (!f.isSuccess()) ctx.close();
                            });
                }, 200, TimeUnit.MILLISECONDS);
            } catch (RuntimeException cause) {
                item.done();
                throw cause;
            }
        }

        private final class Work {
            private ScheduledFuture<?> timer;
            private boolean completed;

            void done() {
                // 쓰기 완료와 channelInactive가 서로 가까운 시점에 발생해도 카운터를 한 번만 줄입니다.
                if (completed) return;
                completed = true;
                if (timer != null) timer.cancel(false);
                work.remove(this);
                finish();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            for (Work item : new ArrayList<>(work)) item.done();
            if (counted) { counted = false; connections.decrementAndGet(); }
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.log(System.Logger.Level.WARNING, "event=channel_failed channel="
                    + ctx.channel().id().asShortText() + " cause=" + cause.getClass().getSimpleName());
            ctx.close();
        }
    }

    public static void main(String[] args) throws Exception {
        ManagedEchoServer app = new ManagedEchoServer(ServerSettings.fromEnvironment());
        // finally와 종료 훅이 모두 close를 호출할 수 있으므로 한 번만 수행하도록 동기화합니다.
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "netty-shutdown"));
        try {
            app.start();
            System.out.println("Enter 또는 정상 종료 신호로 종료합니다.");
            System.in.read();
        } finally {
            app.close();
        }
    }
}
