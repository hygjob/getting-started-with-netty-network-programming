package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.CorruptedFrameException;

// 일반 요청은 같은 본문으로 응답하고 PING에는 같은 ID의 PONG을 보냅니다.
public final class BinaryEchoServer {
    public static void main(String[] args) throws Exception {
        // no-pong은 13장에서 응답 제한 시간을 관찰하기 위한 실습 옵션입니다.
        boolean answerPing = args.length == 0 || !args[0].equals("no-pong");
        EventLoopGroup acceptor = new MultiThreadIoEventLoopGroup(
                1, NioIoHandler.newFactory());
        EventLoopGroup workers = new MultiThreadIoEventLoopGroup(
                2, NioIoHandler.newFactory());
        Channel server = null;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(acceptor, workers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            WireProtocol.install(ch.pipeline());
                            ch.pipeline().addLast(new EchoHandler(answerPing));
                        }
                    });
            ChannelFuture bind = bootstrap.bind("127.0.0.1", 8000);
            server = bind.channel();
            bind.sync();
            System.out.println("바이너리 서버 시작. Enter: 종료");
            System.in.read();
        } finally {
            if (server != null) server.close().syncUninterruptibly();
            workers.shutdownGracefully();
            acceptor.shutdownGracefully();
            workers.terminationFuture().syncUninterruptibly();
            acceptor.terminationFuture().syncUninterruptibly();
        }
    }

    private static final class EchoHandler
            extends SimpleChannelInboundHandler<WireMessage> {
        private final boolean answerPing;

        EchoHandler(boolean answerPing) {
            this.answerPing = answerPing;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WireMessage msg) {
            if (msg.type() == WireMessage.REQUEST) {
                // 응답은 요청의 ID를 그대로 돌려 요청과 결과를 연결합니다.
                ctx.writeAndFlush(new WireMessage(
                        WireMessage.RESPONSE, msg.requestId(), msg.body()))
                        .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            } else if (msg.type() == WireMessage.PING) {
                if (answerPing) {
                    ctx.writeAndFlush(new WireMessage(
                            WireMessage.PONG, msg.requestId(), ""))
                            .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                }
            } else {
                // 알려진 타입이어도 서버가 받을 방향이 아니면 연결을 닫습니다.
                throw new CorruptedFrameException("서버가 받을 수 없는 메시지 타입");
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("연결 종료: " + cause);
            ctx.close();
        }
    }
}
