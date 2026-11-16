package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

// 서버를 구성하고, main 스레드에서 시작과 종료의 경계를 관리합니다.
public final class EchoServer {
    public static void main(String[] args) throws Exception {
        // 연결 수락용 이벤트 루프 1개를 자바 NIO 방식으로 구성합니다.
        EventLoopGroup acceptors =
                new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        // 연결별 I/O를 이벤트 루프 2개에 배정합니다.
        EventLoopGroup workers =
                new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        // 바인딩 도중 실패해도 생성된 대기 채널을 finally에서 닫을 수 있도록 보관합니다.
        Channel serverChannel = null;

        try {
            // 설정 객체를 만드는 것만으로 포트가 열리지는 않습니다.
            ServerBootstrap bootstrap = new ServerBootstrap();
            // 수락용 그룹과 연결별 I/O 그룹을 순서대로 지정합니다.
            bootstrap.group(acceptors, workers)
                    // 연결을 받아들이는 NIO 서버 대기 채널을 선택합니다.
                    .channel(NioServerSocketChannel.class)
                    // 서버가 새로 받아들이는 각 연결의 파이프라인 구성을 지정합니다.
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            // 연결마다 별도의 에코 핸들러를 생성해 파이프라인에 추가합니다.
                            channel.pipeline().addLast(new RetainedEchoHandler());
                        }
                    });

            // 루프백의 8000번 포트에 비동기 바인딩을 요청합니다.
            ChannelFuture bindFuture = bootstrap.bind("127.0.0.1", 8000);
            serverChannel = bindFuture.channel();
            // main 스레드에서 바인딩 완료를 기다립니다. 실패하면 예외가 전파됩니다.
            bindFuture.sync();

            System.out.println("Netty 서버 대기: " + serverChannel.localAddress());
            System.out.println("서버 터미널에서 Enter를 누르면 종료합니다.");
            // main은 종료 입력을 기다리고, 네트워크 처리는 이벤트 루프가 계속 수행합니다.
            System.in.read();
        } finally {
            // 정상 종료와 예외 발생 모두에서 새 연결의 입구인 대기 채널부터 닫습니다.
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
            // 두 그룹에 먼저 종료를 요청합니다. 호출 즉시 종료가 끝나는 것은 아닙니다.
            acceptors.shutdownGracefully();
            workers.shutdownGracefully();
            // main에서 두 그룹의 종료 완료를 확인합니다. 핸들러 안에서 이렇게 기다리면 안 됩니다.
            acceptors.terminationFuture().syncUninterruptibly();
            workers.terminationFuture().syncUninterruptibly();
        }
        System.out.println("Netty 서버를 종료했습니다.");
    }
}
