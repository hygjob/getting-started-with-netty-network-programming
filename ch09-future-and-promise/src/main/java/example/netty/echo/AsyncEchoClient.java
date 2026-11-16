package example.netty.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AsyncEchoClient {
    public static void main(String[] args) throws Exception {
        byte[] request = "Promise로 응답 완료를 확인합니다.".getBytes(StandardCharsets.UTF_8);
        EventLoopGroup group =
                new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        // 연결이나 로컬 쓰기와 별개로 에코 교환 전체의 결과를 표현합니다.
        Promise<Void> exchange = group.next().newPromise();
        Channel channel = null;
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new ResponseHandler(request, exchange));
                        }
                    });

            ChannelFuture connecting = bootstrap.connect("127.0.0.1", 8000);
            Channel connection = connecting.channel();
            channel = connection;

            // 연결 시도 뒤부터 전체 교환에 5초 마감 시간을 둡니다.
            ScheduledFuture<?> deadline = connection.eventLoop().schedule(() -> {
                // 타임아웃이 먼저 결과를 확정한 경우에만 연결을 닫습니다.
                if (exchange.tryFailure(new TimeoutException("에코 교환 시간 초과"))) {
                    connection.close();
                }
            }, 5, TimeUnit.SECONDS);
            // 교환이 먼저 끝나면 불필요한 타이머를 취소합니다.
            exchange.addListener(done -> deadline.cancel(false));

            // 연결 완료 콜백에서 쓰기를 시작하므로 이벤트 루프를 막지 않습니다.
            connecting.addListener((ChannelFutureListener) connected -> {
                // 연결 실패나 취소를 전체 교환의 실패로 연결합니다.
                if (!connected.isSuccess()) {
                    exchange.tryFailure(failureOf(connected));
                    connected.channel().close();
                    return;
                }
                // 연결 완료 전에 마감 시간이 지났다면 요청을 새로 보내지 않습니다.
                if (exchange.isDone()) {
                    connected.channel().close();
                    return;
                }
                System.out.println("연결 성공, 요청 쓰기를 시작합니다.");
                ChannelFuture writing = connected.channel()
                        .writeAndFlush(Unpooled.wrappedBuffer(request));
                // 로컬 쓰기의 실패는 교환 실패이지만, 성공만으로 응답 완료를 뜻하지는 않습니다.
                writing.addListener((ChannelFutureListener) written -> {
                    if (!written.isSuccess()) {
                        exchange.tryFailure(failureOf(written));
                        written.channel().close();
                    } else {
                        System.out.println("로컬 쓰기 성공, 교환 결과는 응답으로 결정합니다.");
                    }
                });
            });

            // main에서만 전체 교환 결과를 기다리고 실패를 예외로 확인합니다.
            exchange.sync();
            System.out.println("에코 교환 성공");
        } finally {
            if (channel != null) {
                channel.close().syncUninterruptibly();
            }
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    // 취소된 작업도 호출자가 이해할 수 있는 실패 원인으로 변환합니다.
    private static Throwable failureOf(ChannelFuture future) {
        return future.isCancelled()
                ? new CancellationException("채널 작업이 취소되었습니다.")
                : future.cause();
    }

    private static final class ResponseHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final byte[] expected;
        // 수신 이벤트 사이에 응답 조각을 누적할 배열입니다.
        private final byte[] received;
        private final Promise<Void> exchange;
        private int count;

        private ResponseHandler(byte[] expected, Promise<Void> exchange) {
            this.expected = expected.clone();
            this.received = new byte[expected.length];
            this.exchange = exchange;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            // 이미 끝난 교환의 늦은 입력은 무시합니다. 수신 버퍼는 기반 클래스가 해제합니다.
            if (exchange.isDone()) {
                return;
            }
            int length = msg.readableBytes();
            // 배열에 복사하기 전에 기대 길이 초과를 검사합니다.
            if (length > received.length - count) {
                exchange.tryFailure(new IllegalStateException("예상보다 긴 응답입니다."));
                ctx.close();
                return;
            }
            // 이번 조각을 이전 응답 뒤에 붙여 TCP의 분할 수신을 처리합니다.
            msg.readBytes(received, count, length);
            count += length;
            if (count == received.length) {
                // 길이와 내용이 모두 맞아야 전체 교환의 성공을 기록합니다.
                if (Arrays.equals(expected, received)) {
                    System.out.println("응답 수신: "
                            + new String(received, StandardCharsets.UTF_8));
                    exchange.trySuccess(null);
                } else {
                    exchange.tryFailure(new IllegalStateException("응답 내용이 다릅니다."));
                }
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // 정상 응답이 이미 성공을 확정했다면 tryFailure는 결과를 덮어쓰지 않습니다.
            exchange.tryFailure(new EOFException("응답 완료 전 연결이 종료되었습니다."));
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // 핸들러 오류도 전체 교환 결과에 반영한 뒤 연결을 정리합니다.
            exchange.tryFailure(cause);
            ctx.close();
        }
    }
}
