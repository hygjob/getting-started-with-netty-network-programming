package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.concurrent.TimeUnit;

// 본문에 따라 응답 지연을 다르게 두어 도착 순서와 시간 초과를 관찰합니다.
public final class RpcDemoServer {
    public static void main(String[] args) throws Exception {
        EventLoopGroup acceptor = new MultiThreadIoEventLoopGroup(
                1, NioIoHandler.newFactory());
        EventLoopGroup workers = new MultiThreadIoEventLoopGroup(
                2, NioIoHandler.newFactory());
        Channel server = null;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap().group(acceptor, workers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            WireProtocol.install(ch.pipeline());
                            ch.pipeline().addLast(new DelayedReplyHandler());
                        }
                    });
            ChannelFuture bind = bootstrap.bind("127.0.0.1", 8000);
            server = bind.channel();
            bind.sync();
            System.out.println("요청·응답 서버 시작. Enter로 종료합니다.");
            System.in.read();
        } finally {
            if (server != null) server.close().syncUninterruptibly();
            workers.shutdownGracefully();
            acceptor.shutdownGracefully();
            workers.terminationFuture().syncUninterruptibly();
            acceptor.terminationFuture().syncUninterruptibly();
        }
    }

    private static final class DelayedReplyHandler
            extends SimpleChannelInboundHandler<WireMessage> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WireMessage msg) {
            if (msg.type() == WireMessage.PING) {
                ctx.writeAndFlush(new WireMessage(WireMessage.PONG, msg.requestId(), ""))
                        .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                return;
            }
            if (msg.type() != WireMessage.REQUEST) {
                throw new CorruptedFrameException("요청이 아닌 메시지");
            }
            long delay = switch (msg.body()) {
                case "slow" -> 1200;
                case "timeout" -> 600;
                default -> 30;
            };
            // Thread.sleep()으로 이벤트 루프를 멈추지 않고 예약 작업으로 지연합니다.
            ctx.executor().schedule(() -> {
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new WireMessage(
                            WireMessage.RESPONSE, msg.requestId(), "응답: " + msg.body()))
                            .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println(cause);
            ctx.close();
        }
    }
}
