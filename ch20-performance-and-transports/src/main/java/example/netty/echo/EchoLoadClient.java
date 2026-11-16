package example.netty.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Promise;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

// 연결 수립은 제외하고, 요청 ID별 송신 시각과 응답 시각으로 지연을 계산합니다.
public final class EchoLoadClient {
    public static void main(String[] args) throws Exception {
        int window = args.length == 0 ? 16 : Integer.parseInt(args[0]);
        if (window < 1 || window > 128) throw new IllegalArgumentException("window: 1~128");
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Promise<String> result = group.next().newPromise();
        Channel channel = null;
        try {
            Bootstrap bootstrap = new Bootstrap().group(group).channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            WireProtocol.install(ch.pipeline());
                            ch.pipeline().addLast(new Probe(window, result));
                        }
                    });
            ChannelFuture connect = bootstrap.connect("127.0.0.1", 8000);
            channel = connect.channel();
            connect.sync();
            System.out.println(result.get(30, TimeUnit.SECONDS));
        } finally {
            if (channel != null) channel.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    // 응답이 와야 다음 요청을 보내는 닫힌 루프 방식으로 동시 요청 수를 고정합니다.
    private static final class Probe extends SimpleChannelInboundHandler<WireMessage> {
        private static final int WARMUP = 100;
        private static final int COUNT = 2000;
        private final String body = "x".repeat(64);
        private final long[] sentAt = new long[WARMUP + COUNT + 1];
        private final boolean[] seen = new boolean[sentAt.length];
        private final long[] latency = new long[COUNT];
        private final int window;
        private final Promise<String> result;
        private int issued;
        private int received;
        private int limit = WARMUP;
        private long started;

        Probe(int window, Promise<String> result) { this.window = window; this.result = result; }

        @Override
        public void channelActive(ChannelHandlerContext ctx) { pump(ctx); }

        private void pump(ChannelHandlerContext ctx) {
            while (!result.isDone() && issued < limit && issued - received < window) {
                int id = ++issued;
                sentAt[id] = System.nanoTime();
                ctx.write(new WireMessage(WireMessage.REQUEST, id, body)).addListener(f -> {
                    if (!f.isSuccess()) { result.tryFailure(f.cause()); ctx.close(); }
                });
            }
            // 여러 write를 모은 뒤 한 번 flush합니다. 처리량과 지연의 절충을 측정하기 위한 방법입니다.
            ctx.flush();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WireMessage msg) {
            long id = msg.requestId();
            if (msg.type() != WireMessage.RESPONSE || id <= 0 || id > issued
                    || seen[(int) id] || !body.equals(msg.body())) {
                result.tryFailure(new IllegalStateException("잘못되거나 중복된 응답"));
                ctx.close();
                return;
            }
            long now = System.nanoTime();
            seen[(int) id] = true;
            received++;
            if (id > WARMUP) latency[(int) id - WARMUP - 1] = now - sentAt[(int) id];
            if (received == WARMUP) {
                // 준비 단계 응답과 측정 단계 응답을 섞지 않습니다.
                started = now;
                limit = WARMUP + COUNT;
            }
            if (received == WARMUP + COUNT) {
                double seconds = (now - started) / 1_000_000_000.0;
                Arrays.sort(latency);
                result.trySuccess(String.format(Locale.ROOT,
                        "count=%d window=%d throughput=%.1f req/s p50=%.3fms p95=%.3fms p99=%.3fms",
                        COUNT, window, COUNT / seconds, percentile(.50), percentile(.95), percentile(.99)));
                ctx.close();
                return;
            }
            pump(ctx);
        }

        private double percentile(double fraction) {
            return latency[(int) Math.ceil(COUNT * fraction) - 1] / 1_000_000.0;
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            result.tryFailure(new ClosedChannelException());
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            result.tryFailure(cause);
            ctx.close();
        }
    }
}
