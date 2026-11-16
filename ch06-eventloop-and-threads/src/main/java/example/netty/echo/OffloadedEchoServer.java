package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutor;

import java.util.concurrent.RejectedExecutionException;

// 느린 작업을 I/O 스레드에서 실행할 때와 별도 실행기로 옮길 때를 비교합니다.
public final class OffloadedEchoServer {
    public static void main(String[] args) throws Exception {
        // inline 인수를 주면 일부러 I/O 이벤트 루프에서 느린 작업을 실행합니다.
        boolean offload = args.length == 0 || !"inline".equals(args[0]);
        EventLoopGroup acceptors =
                new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workers =
                new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        // 느린 업무를 실행할 전용 스레드 두 개를 준비합니다.
        DefaultEventExecutorGroup business = new DefaultEventExecutorGroup(2);
        Channel server = null;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(acceptors, workers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 연결 초기화 때 익스큐터 하나를 선택해 파이프라인에 등록합니다.
                            EventExecutor executor = offload ? business.next() : null;
                            // 핸들러 자체는 I/O 이벤트 루프에서 호출되며, 내부에서 업무만 분리합니다.
                            ch.pipeline().addLast("slowEcho", new SlowEcho(executor));
                        }
                    });
            ChannelFuture binding = bootstrap.bind("127.0.0.1", 8000);
            server = binding.channel();
            // main에서 서버 시작을 확인한 뒤 종료 입력을 기다립니다.
            binding.sync();
            System.out.println("offload=" + offload + ", 교환이 끝난 뒤 Enter로 종료합니다.");
            System.in.read();
        } finally {
            if (server != null) {
                server.close().syncUninterruptibly();
            }
            // 업무 결과를 I/O 쪽에 돌려줄 수 있도록 업무 실행기를 먼저 정리합니다.
            business.shutdownGracefully().syncUninterruptibly();
            acceptors.shutdownGracefully();
            workers.shutdownGracefully();
            acceptors.terminationFuture().syncUninterruptibly();
            workers.terminationFuture().syncUninterruptibly();
        }
    }

    private static final class SlowEcho extends SimpleChannelInboundHandler<ByteBuf> {
        private final EventExecutor business;
        // 이 값은 메시지 수가 아니라 이 연결에서 받은 수신 청크 수입니다.
        private long chunks;

        private SlowEcho(EventExecutor business) {
            this.business = business;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            long sequence = ++chunks;
            // 수신 ByteBuf는 메서드 종료 후 자동 해제되므로 업무에는 복사한 배열을 넘깁니다.
            byte[] data = new byte[msg.readableBytes()];
            msg.readBytes(data);
            System.out.println("I/O chunk=" + sequence
                    + " thread=" + Thread.currentThread().getName());

            // 이 작업만 별도 실행처리 합니다. data는 작업이 끝날 때까지 유지됩니다.
            Runnable job = () -> {
                System.out.println("업무 id=" + ctx.channel().id().asShortText()
                        + " chunk=" + sequence
                        + " thread=" + Thread.currentThread().getName()
                        + " onIoLoop=" + ctx.channel().eventLoop().inEventLoop());
                try {
                    // 느린 업무를 흉내 냅니다. inline 모드에서는 같은 루프의 다른 연결도 지연됩니다.
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // 중단 요청을 보존하고 연결 정리는 I/O 이벤트 루프로 돌려보냅니다.
                    Thread.currentThread().interrupt();
                    returnToIo(ctx, () -> ctx.close());
                    return;
                }
                returnToIo(ctx, () -> {
                    // 업무 처리 중 연결이 닫혔다면 더 이상 응답을 만들지 않습니다.
                    if (ctx.channel().isActive()) {
                        ctx.writeAndFlush(Unpooled.wrappedBuffer(data))
                                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                    }
                });
            };

            if (business == null) {
                // 현재 스레드 실행하게 되며 1초를 기다리게 됩니다.
                job.run();
            } else {
                try {
                    // 작업을 제출한 뒤 수신 핸들러는 곧바로 돌아가 다른 I/O를 처리할 수 있습니다.
                    // 익스큐터의 스레드에서 실행
                    business.execute(job);
                } catch (RejectedExecutionException e) {
                    // 종료 등으로 업무 제출이 거부되면 오류를 전달하고 연결을 닫습니다.
                    ctx.fireExceptionCaught(e);
                    ctx.close();
                }
            }
        }

        private static void returnToIo(ChannelHandlerContext ctx, Runnable action) {
            try {
                // 응답 처리와 채널 상태 확인을 해당 채널의 I/O 이벤트 루프에서 실행합니다.
                ctx.channel().eventLoop().execute(action);
            } catch (RejectedExecutionException e) {
                // I/O 실행기가 이미 종료되었다면 결과 작업을 실행할 수 없음을 기록합니다.
                System.err.println("I/O 종료로 결과 처리를 제출하지 못했습니다: " + e);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("업무 오류: " + cause);
            ctx.close();
        }
    }
}
