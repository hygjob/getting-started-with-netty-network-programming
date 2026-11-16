package example.netty.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// 한 연결에서 요청을 하나씩 보내고, 응답을 모두 받은 뒤에만 다음 요청을 시작합니다.
public final class HttpApiClient {
    public record Reply(int status, String body) { }

    public static void main(String[] args) throws Exception {
        run(null, "localhost", 8080);
    }

    public static void run(SslContext tls, String host, int port) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        ReplyHandler replies = new ReplyHandler(host + ":" + port);
        Channel channel = null;
        try {
            Bootstrap bootstrap = new Bootstrap().group(group).channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            if (tls != null) {
                                // 연결 IP와 별개로 검증할 피어 이름을 전달합니다.
                                SslHandler ssl = tls.newHandler(ch.alloc(), host, port);
                                ssl.setHandshakeTimeoutMillis(5000);
                                ch.pipeline().addLast(ssl);
                            }
                            ch.pipeline().addLast(new HttpClientCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
                            ch.pipeline().addLast(replies);
                        }
                    });
            ChannelFuture connect = bootstrap.connect("127.0.0.1", port);
            channel = connect.channel();
            connect.sync();
            SslHandler ssl = channel.pipeline().get(SslHandler.class);
            if (ssl != null) ssl.handshakeFuture().sync();
            System.out.println("같은 채널 사용: " + channel.id());
            System.out.println(replies.get("/hello").get(5, TimeUnit.SECONDS));
            System.out.println(replies.get("/health").get(5, TimeUnit.SECONDS));
        } finally {
            if (channel != null) channel.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static final class ReplyHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final String hostHeader;
        private volatile ChannelHandlerContext context;
        private Promise<Reply> pending;
        private ScheduledFuture<?> timer;
        private boolean reusable = true;

        ReplyHandler(String hostHeader) { this.hostHeader = hostHeader; }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) { context = ctx; }

        Future<Reply> get(String path) {
            ChannelHandlerContext ctx = context;
            Promise<Reply> result = ctx.executor().newPromise();
            ctx.executor().execute(() -> {
                if (!reusable || !ctx.channel().isActive() || pending != null) {
                    result.tryFailure(new IllegalStateException("연결을 사용할 수 없습니다."));
                    return;
                }
                pending = result;
                timer = ctx.executor().schedule(() -> {
                    // 늦은 첫 응답을 다음 요청 결과로 오인하지 않도록 연결을 닫습니다.
                    reusable = false;
                    fail(new TimeoutException("HTTP 응답 시간 초과"));
                    ctx.close();
                }, 3, TimeUnit.SECONDS);
                FullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.GET, path);
                request.headers().set(HttpHeaderNames.HOST, hostHeader);
                request.headers().set(HttpHeaderNames.ACCEPT, "application/json");
                HttpUtil.setContentLength(request, 0);
                ctx.writeAndFlush(request).addListener(f -> {
                    if (!f.isSuccess()) {
                        reusable = false;
                        fail(f.cause());
                        ctx.close();
                    }
                });
            });
            return result;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
            if (!response.decoderResult().isSuccess()) {
                fail(response.decoderResult().cause());
                ctx.close();
                return;
            }
            if (response.status().code() < 200) return;
            Promise<Reply> result = take();
            reusable = HttpUtil.isKeepAlive(response);
            if (result == null) {
                ctx.close();
                return;
            }
            // 본문을 String으로 복사하므로 수신 객체가 해제된 뒤에도 결과를 사용할 수 있습니다.
            result.trySuccess(new Reply(response.status().code(),
                    response.content().toString(StandardCharsets.UTF_8)));
            if (!reusable) ctx.close();
        }

        private Promise<Reply> take() {
            Promise<Reply> result = pending;
            pending = null;
            if (timer != null) { timer.cancel(false); timer = null; }
            return result;
        }

        private void fail(Throwable cause) {
            Promise<Reply> result = take();
            if (result != null) result.tryFailure(cause);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            reusable = false;
            fail(new ClosedChannelException());
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            reusable = false;
            fail(cause);
            ctx.close();
        }
    }
}
