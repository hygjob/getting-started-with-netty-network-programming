package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;

// TLS 핸들러 뒤에 11장의 바이너리 코덱을 그대로 올립니다.
public final class TlsEchoServer {
    public static void main(String[] args) throws Exception {
        SslContext tls = TlsContexts.server(args.length > 0 && args[0].equals("mtls"));
        EventLoopGroup acceptor = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workers = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        Channel server = null;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap().group(acceptor, workers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // SslHandler는 연결마다 새로 만들고 파이프라인 가장 앞에 둡니다.
                            SslHandler ssl = tls.newHandler(ch.alloc());
                            ssl.setHandshakeTimeoutMillis(5000);
                            ch.pipeline().addLast(ssl);
                            WireProtocol.install(ch.pipeline());
                            ch.pipeline().addLast(new EchoHandler());
                            ssl.handshakeFuture().addListener(f -> {
                                if (!f.isSuccess()) {
                                    System.err.println("TLS 실패: " + f.cause());
                                    ch.close();
                                }
                            });
                        }
                    });
            ChannelFuture bind = bootstrap.bind("127.0.0.1", 8443);
            server = bind.channel();
            bind.sync();
            System.out.println("TLS 에코 서버 시작. Enter: 종료");
            System.in.read();
        } finally {
            if (server != null) server.close().syncUninterruptibly();
            workers.shutdownGracefully();
            acceptor.shutdownGracefully();
            workers.terminationFuture().syncUninterruptibly();
            acceptor.terminationFuture().syncUninterruptibly();
        }
    }

    private static final class EchoHandler extends SimpleChannelInboundHandler<WireMessage> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WireMessage message) {
            if (message.type() != WireMessage.REQUEST) {
                ctx.close();
                return;
            }
            ctx.writeAndFlush(new WireMessage(WireMessage.RESPONSE,
                    message.requestId(), message.body()))
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println(cause);
            ctx.close();
        }
    }
}
