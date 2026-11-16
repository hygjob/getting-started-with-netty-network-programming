package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Base64;
import java.security.MessageDigest;
import java.security.SecureRandom;

// HTTP로 페이지와 접속 승인을 처리하고, 핸드셰이크가 끝난 채널만 채팅 그룹에 넣습니다.
public final class WebSocketChatServer {
    public static void main(String[] args) throws Exception {
        boolean secure = args.length > 0 && args[0].equals("tls");
        SslContext tls = secure ? TlsContexts.server(false) : null;
        int port = secure ? 8443 : 8080;
        String authority = "localhost:" + port;
        String origin = (secure ? "https://" : "http://") + authority;
        String password = System.getenv("NETTY_CHAT_PASSWORD");
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException("NETTY_CHAT_PASSWORD를 12자 이상으로 설정하십시오.");
        }
        byte[] secret = password.getBytes(StandardCharsets.UTF_8);
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        // 암호 자체가 아니라 시작 시 만든 임의 토큰을 쿠키로 발급합니다.
        String sessionToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        byte[] page;
        try (InputStream input = WebSocketChatServer.class.getResourceAsStream("/chat.html")) {
            if (input == null) throw new IllegalStateException("chat.html 리소스가 필요합니다.");
            page = input.readNBytes(64 * 1024 + 1);
            if (page.length > 64 * 1024) throw new IllegalArgumentException("페이지 크기 초과");
        }
        ChannelGroup members = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
        EventLoopGroup acceptor = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workers = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        Channel server = null;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap().group(acceptor, workers)
                    .channel(NioServerSocketChannel.class)
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
                            // 제어 프레임이 프로토콜 핸들러에서 소비되기 전에 읽기를 관찰합니다.
                            ch.pipeline().addLast(new IdleStateHandler(30, 10, 0));
                            // 페이지·접속 승인·업그레이드 검사를 HTTP 단계에서 먼저 수행합니다.
                            ch.pipeline().addLast(new UpgradeGate(page, authority, origin, secret, sessionToken, secure));
                            ch.pipeline().addLast(new WebSocketServerProtocolHandler(
                                    WebSocketServerProtocolConfig.newBuilder()
                                            .websocketPath("/ws")
                                            .checkStartsWith(false)
                                            .allowExtensions(false)
                                            .maxFramePayloadLength(16 * 1024)
                                            .handshakeTimeoutMillis(5000)
                                            .forceCloseTimeoutMillis(3000)
                                            .build()));
                            ch.pipeline().addLast(new WebSocketFrameAggregator(16 * 1024));
                            ch.pipeline().addLast(new ChatHandler(members));
                        }
                    });
            ChannelFuture bind = bootstrap.bind("127.0.0.1", port);
            server = bind.channel();
            bind.sync();
            System.out.println(origin + "/ 에 접속하십시오. Enter: 종료");
            System.in.read();
        } finally {
            if (server != null) server.close().syncUninterruptibly();
            members.close().awaitUninterruptibly();
            workers.shutdownGracefully();
            acceptor.shutdownGracefully();
            workers.terminationFuture().syncUninterruptibly();
            acceptor.terminationFuture().syncUninterruptibly();
        }
    }

    // 페이지 제공, 접속 승인, Origin, 쿠키 검사를 업그레이드 전에 수행합니다.
    private static final class UpgradeGate extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final byte[] page;
        private final String authority;
        private final String allowedOrigin;
        private final byte[] secret;
        private final String sessionToken;
        private final boolean secure;

        UpgradeGate(byte[] page, String authority, String allowedOrigin,
                    byte[] secret, String sessionToken, boolean secure) {
            this.page = page;
            this.authority = authority;
            this.allowedOrigin = allowedOrigin;
            this.secret = secret;
            this.sessionToken = sessionToken;
            this.secure = secure;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            if (!request.decoderResult().isSuccess()) {
                reject(ctx, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            if (!authority.equals(request.headers().get(HttpHeaderNames.HOST))) {
                reject(ctx, HttpResponseStatus.FORBIDDEN);
                return;
            }
            // 일반 HTML 페이지 요청은 채팅 그룹에 넣지 않고 HTTP로 끝냅니다.
            if (request.uri().equals("/") && request.method().equals(HttpMethod.GET)) {
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(page));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                HttpUtil.setContentLength(response, page.length);
                HttpUtil.setKeepAlive(response, false);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            // Origin은 인증이 아니라 브라우저 페이지 출처를 검사하는 정책입니다.
            List<String> origins = request.headers().getAll(HttpHeaderNames.ORIGIN);
            boolean sameOrigin = origins.size() == 1 && allowedOrigin.equals(origins.get(0));
            if (request.uri().equals("/session")) {
                if (!request.method().equals(HttpMethod.POST) || !sameOrigin
                        || !MessageDigest.isEqual(secret, ByteBufUtil.getBytes(request.content()))) {
                    reject(ctx, HttpResponseStatus.FORBIDDEN);
                    return;
                }
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                response.headers().set(HttpHeaderNames.SET_COOKIE,
                        "netty-chat-session=" + sessionToken
                                + "; Path=/; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : ""));
                response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
                HttpUtil.setContentLength(response, 0);
                HttpUtil.setKeepAlive(response, false);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            if (!request.uri().equals("/ws")) {
                reject(ctx, HttpResponseStatus.NOT_FOUND);
                return;
            }
            if (!request.method().equals(HttpMethod.GET) || !sameOrigin) {
                reject(ctx, HttpResponseStatus.FORBIDDEN);
                return;
            }
            boolean authenticated = false;
            String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE, "");
            // 서버의 실제 세션 토큰과 사용자가 보낸 토큰 값을 비교합니다.            
            for (Cookie cookie : ServerCookieDecoder.STRICT.decode(cookieHeader)) {
                if (cookie.name().equals("netty-chat-session") && MessageDigest.isEqual(
                        sessionToken.getBytes(StandardCharsets.US_ASCII),
                        cookie.value().getBytes(StandardCharsets.US_ASCII))) {
                    authenticated = true;
                }
            }
            if (!authenticated) {
                reject(ctx, HttpResponseStatus.FORBIDDEN);
                return;
            }
            // 승인된 HTTP 요청의 소유권을 다음 핸들러로 넘깁니다.
            ctx.fireChannelRead(request.retain());
        }

        private void reject(ChannelHandlerContext ctx, HttpResponseStatus status) {
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
            HttpUtil.setContentLength(response, 0);
            HttpUtil.setKeepAlive(response, false);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    // TCP 활성화가 아니라 핸드셰이크 완료 뒤에만 채팅 그룹에 등록합니다.
    private static final class ChatHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private final ChannelGroup members;
        private boolean joined;

        ChatHandler(ChannelGroup members) { this.members = members; }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                joined = true;
                members.add(ctx.channel());
                broadcast("JOIN " + ctx.channel().id().asShortText());
            } else if (event instanceof IdleStateEvent idle) {
                if (idle.state() == IdleState.READER_IDLE) {
                    ctx.close();
                } else if (joined && idle.state() == IdleState.WRITER_IDLE) {
                    ctx.writeAndFlush(new PingWebSocketFrame(
                            Unpooled.copiedBuffer("hb", StandardCharsets.UTF_8)))
                            .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                }
            } else {
                ctx.fireUserEventTriggered(event);
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (!(frame instanceof TextWebSocketFrame text)) {
                ctx.writeAndFlush(new CloseWebSocketFrame(1003, "text only"))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            // 프레임 한도와 별개로 업무 본문 한도를 UTF-8 바이트 수로 검사합니다.
            if (text.content().readableBytes() > 1024 || text.text().isBlank()) {
                ctx.writeAndFlush(new CloseWebSocketFrame(1008, "invalid chat message"))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            broadcast("[" + ctx.channel().id().asShortText() + "] " + text.text());
        }

        private void broadcast(String text) {
            ChannelGroupFuture writes = members.writeAndFlush(new TextWebSocketFrame(text));
            writes.addListener(f -> {
                for (ChannelFuture write : writes) {
                    if (!write.isSuccess()) write.channel().close();
                }
            });
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (joined) {
                members.remove(ctx.channel());
                broadcast("LEAVE " + ctx.channel().id().asShortText());
            }
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("WebSocket 오류: " + cause);
            ctx.close();
        }
    }
}
