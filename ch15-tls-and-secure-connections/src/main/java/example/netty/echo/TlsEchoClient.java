package example.netty.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Promise;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeUnit;

// 연결 IP는 127.0.0.1이고 인증서에서 확인할 이름은 localhost입니다.
public final class TlsEchoClient {
    public static void main(String[] args) throws Exception {
        boolean mutual = args.length > 0 && args[0].equals("mtls");
        String peerHost = args.length > 1 ? args[1] : "localhost";
        SslContext tls = TlsContexts.client(mutual);
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Promise<String> response = group.next().newPromise();
        Channel channel = null;
        try {
            Bootstrap bootstrap = new Bootstrap().group(group).channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 서버와 달리 검증할 피어 이름을 함께 전달합니다.
                            SslHandler ssl = tls.newHandler(ch.alloc(), peerHost, 8443);
                            ssl.setHandshakeTimeoutMillis(5000);
                            ch.pipeline().addLast(ssl);
                            WireProtocol.install(ch.pipeline());
                            ch.pipeline().addLast(new ReplyHandler(response));
                        }
                    });
            ChannelFuture connect = bootstrap.connect("127.0.0.1", 8443);
            channel = connect.channel();
            connect.sync();
            SslHandler ssl = channel.pipeline().get(SslHandler.class);
            ssl.handshakeFuture().sync(); // main 스레드에서 TLS 준비를 기다립니다.
            System.out.println("TLS 준비: " + ssl.engine().getSession().getProtocol());
            channel.writeAndFlush(new WireMessage(WireMessage.REQUEST, 1, "암호화된 에코"))
                    .addListener(f -> {
                        if (!f.isSuccess()) response.tryFailure(f.cause());
                    });
            System.out.println(response.get(5, TimeUnit.SECONDS));
        } finally {
            if (channel != null) channel.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static final class ReplyHandler extends SimpleChannelInboundHandler<WireMessage> {
        private final Promise<String> response;
        ReplyHandler(Promise<String> response) { this.response = response; }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WireMessage msg) {
            if (msg.type() == WireMessage.RESPONSE && msg.requestId() == 1) {
                response.trySuccess(msg.body());
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
