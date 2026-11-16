package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

// 대기 채널과 연결 채널의 생명주기를 같은 형식의 로그로 비교합니다.
public final class LifecycleEchoServer {
    public static void main(String[] args) throws Exception {
        // 새 연결 수락과 연결별 I/O를 서로 다른 그룹에 맡깁니다.
        EventLoopGroup acceptors =
                new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workers =
                new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        Channel server = null;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(acceptors, workers)
                    .channel(NioServerSocketChannel.class)
                    // option은 서버 대기 채널에 적용합니다. 연결 수락 대기열의 크기를 요청합니다.
                    .option(ChannelOption.SO_BACKLOG, 128)
                    // childOption은 받아들인 연결에 적용합니다. 크기가 작은 쓰기의 지연을 줄이도록 설정합니다.
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    // 운영체제의 TCP 연결 확인 기능입니다. 애플리케이션 하트비트와는 구분합니다.
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    // 서버 대기 채널의 이벤트에는 listen 표시를 붙입니다.
                    .handler(new LifecycleTrace("listen"))
                    // 새 연결마다 파이프라인을 초기화하고 child 표시의 추적기를 만듭니다.
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast("lifecycle", new LifecycleTrace("child"));
                            // LifecycleTrace 다음에는 EchoServerHandler에서 에코 응답을 처리합니다.
                            ch.pipeline().addLast("echo", new EchoServerHandler());
                        }
                    });
            ChannelFuture binding = bootstrap.bind("127.0.0.1", 8000);
            server = binding.channel();
            // 바인딩 성공 여부는 main에서 기다려 확인합니다.
            binding.sync();
            System.out.println("생명주기 서버가 준비되었습니다. Enter로 종료합니다.");
            System.in.read();
        } finally {
            if (server != null) {
                // 새 연결을 받는 대기 채널을 먼저 닫습니다.
                server.close().syncUninterruptibly();
            }
            // 두 그룹에 종료를 요청한 뒤 각각의 종료 완료를 확인합니다.
            acceptors.shutdownGracefully();
            workers.shutdownGracefully();
            acceptors.terminationFuture().syncUninterruptibly();
            workers.terminationFuture().syncUninterruptibly();
        }
    }

    private static final class LifecycleTrace extends ChannelInboundHandlerAdapter {
        private final String role;

        private LifecycleTrace(String role) {
            this.role = role;
        }

        // 이벤트가 발생한 시점의 채널 ID, 상태, 로컬·상대 주소를 함께 기록합니다.
        private void print(ChannelHandlerContext ctx, String event) {
            Channel ch = ctx.channel();
            System.out.printf("[ %s ] [ %s ] [ id=%s ] open=%s registered=%s active=%s local=%s remote=%s%n",
                    role, event, ch.id().asShortText(), ch.isOpen(),
                    ch.isRegistered(), ch.isActive(), ch.localAddress(), ch.remoteAddress());
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) {
            print(ctx, "registered <<<<<<<<<<");
            // 추적기가 이벤트를 소비하지 않도록 다음 핸들러에도 등록 이벤트를 전달합니다.
            ctx.fireChannelRegistered();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            print(ctx, "active ////////////////");
            // 통신 활성화 이벤트를 다음 핸들러로 전달합니다.
            ctx.fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            print(ctx, "inactive ___________");
            // 통신 비활성화 이벤트를 다음 핸들러로 전달합니다.
            ctx.fireChannelInactive();
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) {
            print(ctx, "unregistered >>>>>>>>>");
            // 이벤트 루프 등록 해제도 파이프라인의 다음 핸들러에 알립니다.
            ctx.fireChannelUnregistered();
        }
    }
}
