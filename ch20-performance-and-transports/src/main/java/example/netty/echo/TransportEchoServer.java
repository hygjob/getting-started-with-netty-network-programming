package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;

// 선택한 팩터리와 서버 채널을 함께 사용하고 업무 핸들러는 트랜스포트와 상관없이 동일합니다.
public final class TransportEchoServer {
    public static void main(String[] args) throws Exception {
        TransportChoice choice = TransportChoice.select(args.length == 0 ? "nio" : args[0]);
        int workerCount = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        if (workerCount < 1 || workerCount > 64) throw new IllegalArgumentException("worker 수 범위: 1~64");
        EventLoopGroup acceptor = new MultiThreadIoEventLoopGroup(1, choice.factory());
        EventLoopGroup workers = new MultiThreadIoEventLoopGroup(workerCount, choice.factory());
        Channel server = null;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap().group(acceptor, workers)
                    .channel(choice.serverType())
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            WireProtocol.install(ch.pipeline());
                            ch.pipeline().addLast(new Echo());
                        }
                    });
            ChannelFuture bind = bootstrap.bind("127.0.0.1", 8000);
            server = bind.channel();
            bind.sync();
            System.out.println("transport=" + choice.name() + " workers=" + workerCount);
            System.in.read();
        } finally {
            if (server != null) server.close().syncUninterruptibly();
            workers.shutdownGracefully();
            acceptor.shutdownGracefully();
            workers.terminationFuture().syncUninterruptibly();
            acceptor.terminationFuture().syncUninterruptibly();
        }
    }

    private static final class Echo extends SimpleChannelInboundHandler<WireMessage> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WireMessage msg) {
            if (msg.type() != WireMessage.REQUEST) { ctx.close(); return; }
            ctx.writeAndFlush(new WireMessage(WireMessage.RESPONSE, msg.requestId(), msg.body()))
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println(cause);
            ctx.close();
        }
    }
}
