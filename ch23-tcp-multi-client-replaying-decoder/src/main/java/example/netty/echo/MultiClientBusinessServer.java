package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

// 하나의 서버 채널이 여러 TCP 연결을 수락하고, 연결마다 업무 파이프라인을 만듭니다.
public final class MultiClientBusinessServer {
    public static final int DEFAULT_PORT = 9000;

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? DEFAULT_PORT : parsePort(args[0]);
        EventLoopGroup acceptors = new MultiThreadIoEventLoopGroup(
                1, NioIoHandler.newFactory());
        EventLoopGroup workers = new MultiThreadIoEventLoopGroup(
                2, NioIoHandler.newFactory());
        ConnectionRegistry connections = new ConnectionRegistry();
        BusinessService businessService = new BusinessService();
        Channel serverChannel = null;

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(acceptors, workers)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            // 누적 상태가 있으므로 채널마다 새 디코더를 생성해야 합니다.
                            channel.pipeline().addLast("replayDecoder", new ReplayWireDecoder());
                            channel.pipeline().addLast("encoder", new WireEncoder());
                            channel.pipeline().addLast("business",
                                    new BusinessRequestHandler(connections, businessService));
                        }
                    });

            ChannelFuture bind = bootstrap.bind("127.0.0.1", port);
            serverChannel = bind.channel();
            bind.sync();
            System.out.printf("TCP 업무 서버 대기: %s (worker=%d)%n",
                    serverChannel.localAddress(), 2);
            System.out.println("다른 터미널에서 MultiClientDemo를 실행하고 Enter로 종료하십시오.");
            System.in.read();
        } finally {
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
            workers.shutdownGracefully();
            acceptors.shutdownGracefully();
            workers.terminationFuture().syncUninterruptibly();
            acceptors.terminationFuture().syncUninterruptibly();
        }
        System.out.printf("TCP 업무 서버 종료. 마지막 연결 수=%d%n", connections.size());
    }

    private static int parsePort(String value) {
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("포트는 1부터 65535 사이여야 합니다.");
        }
        return port;
    }
}
