package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

// 파이프라인에서 읽기·쓰기·flush의 진행 방향을 로그로 관찰합니다.
public final class PipelineTraceServer {
    public static void main(String[] args) throws Exception {
        // channel 인수를 사용하여 쓰기를 시작할 위치를 바꾸어 실행 경로를 비교합니다.
        boolean fromChannel = args.length > 0 && "channel".equals(args[0]);
        EventLoopGroup acceptors =
                new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workers =
                new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Channel server = null;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(acceptors, workers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // A와 C는 양방향, B는 수신, E는 송신 이벤트를 추적합니다.
                            ch.pipeline().addLast("A", new DuplexTrace());
                            ch.pipeline().addLast("B", new InboundTrace());
                            ch.pipeline().addLast("C", new DuplexTrace());
                            // D는 받은 데이터를 쓰기 경로로 넘깁니다. E는 D보다 뒤에 배치합니다.
                            ch.pipeline().addLast("D", new Echo(fromChannel));
                            ch.pipeline().addLast("E", new OutboundTrace());
                        }
                    });
            ChannelFuture binding = bootstrap.bind("127.0.0.1", 8000);
            server = binding.channel();
            // main에서 바인딩 성공을 확인한 뒤 종료 입력을 기다립니다.
            binding.sync();
            System.out.println("fromChannel=" + fromChannel + ", Enter: 종료");
            System.in.read();
        } finally {
            if (server != null) {
                // 대기 채널을 닫은 다음 두 이벤트 루프 그룹을 종료합니다.
                server.close().syncUninterruptibly();
            }
            acceptors.shutdownGracefully();
            workers.shutdownGracefully();
            acceptors.terminationFuture().syncUninterruptibly();
            workers.terminationFuture().syncUninterruptibly();
        }
    }

    // 핸들러 이름과 채널 ID를 함께 출력해 같은 연결의 호출 순서를 구분합니다.
    private static void log(ChannelHandlerContext ctx, String action) {
        System.out.println(ctx.name() + " " + action
                + " id=" + ctx.channel().id().asShortText());
    }

    // 인바운드와 아웃바운드 이벤트 모두에 반응하는 추적기입니다.
    private static final class DuplexTrace extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            log(ctx, "READ");
            // 수신 메시지의 소유권을 다음 인바운드 핸들러로 전달합니다.
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            log(ctx, "WRITE");
            // 원래 Promise도 함께 넘겨 실제 쓰기의 결과가 호출자에게 전달되도록 합니다.
            ctx.write(msg, promise);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            log(ctx, "FLUSH");            
            ctx.flush();
        }
    }

    // 읽기 경로에만 참여하므로 아웃바운드 쓰기에서는 건너뜁니다.
    private static final class InboundTrace extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            log(ctx, "READ");
            // 수신 메시지의 소유권을 다음 인바운드 핸들러로 전달합니다.
            ctx.fireChannelRead(msg);
        }
    }

    // 쓰기와 flush 경로에만 참여합니다.
    private static final class OutboundTrace extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            log(ctx, "WRITE");
            // 원래 Promise도 함께 넘겨 실제 쓰기의 결과가 호출자에게 전달되도록 합니다.
            ctx.write(msg, promise);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            log(ctx, "FLUSH");            
            ctx.flush();
        }
    }

    private static final class Echo extends ChannelInboundHandlerAdapter {
        private final boolean fromChannel;

        private Echo(boolean fromChannel) {
            this.fromChannel = fromChannel;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            log(ctx, "READ");
            // channel 쓰기는 TAIL 쪽부터 E → C → A로 진행합니다.
            // ctx 쓰기는 현재 D 앞에서 시작하므로 C → A로 진행하고 E는 지나지 않습니다.
            // 받은 메시지를 쓰기 경로에 넘겼으므로 여기서 다시 해제하지 않습니다.
            ChannelFuture writing = fromChannel
                    ? ctx.channel().writeAndFlush(msg)
                    : ctx.writeAndFlush(msg);
            // 완료를 기다리지 않고 쓰기 실패나 취소에 대한 종료 처리를 등록합니다.
            writing.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("에코 오류: " + cause);
            ctx.close();
        }
    }
}
