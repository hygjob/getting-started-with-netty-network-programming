package example.netty.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Promise;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeUnit;

// 요청을 보내고 응답을 Promise로 기다립니다.
public final class BinaryEchoClient {
    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(
                1, NioIoHandler.newFactory());
        Promise<WireMessage> response = group.next().newPromise();
        Channel channel = null;
        try {
            Bootstrap bootstrap = new Bootstrap().group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            WireProtocol.install(ch.pipeline());
                            ch.pipeline().addLast(new ReplyHandler(response));
                        }
                    });
            ChannelFuture connect = bootstrap.connect("127.0.0.1", 8000);
            channel = connect.channel();
            connect.sync();
            channel.writeAndFlush(new WireMessage(WireMessage.REQUEST, 1, "안녕 Netty"))
                    .addListener(f -> {
                        // 쓰기 실패는 응답 Promise의 실패 원인이 됩니다. 쓰기 성공만으로 응답을 완료하지는 않습니다.
                        if (!f.isSuccess()) response.tryFailure(f.cause());
                    });
            // 호출자가 기다리는 시간을 제한합니다. Promise를 실패시키거나 원격 요청을 취소하지는 않습니다.
            System.out.println(response.get(5, TimeUnit.SECONDS));
        } finally {
            if (channel != null) channel.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static final class ReplyHandler
            extends SimpleChannelInboundHandler<WireMessage> {
        private final Promise<WireMessage> response;

        ReplyHandler(Promise<WireMessage> response) {
            this.response = response;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WireMessage msg) {
            if (msg.type() == WireMessage.RESPONSE && msg.requestId() == 1) {
                response.trySuccess(msg);
            } else {
                response.tryFailure(new IllegalStateException("예상하지 못한 응답"));
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            response.tryFailure(new ClosedChannelException());
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            response.tryFailure(cause);
            ctx.close();
        }
    }
}
