package example.netty.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;

import java.nio.charset.StandardCharsets;

// 연결 하나로 에코 요청을 보내고 결과를 확인한 뒤 종료하는 실행 클래스입니다.
public final class EchoClient {
    public static void main(String[] args) throws Exception {
        // 응답 길이를 글자 수가 아닌 UTF-8 바이트 수로 판단하도록 미리 변환합니다.
        byte[] request = "안녕하세요, Netty 4.2!".getBytes(StandardCharsets.UTF_8);
        // 누적 상태를 가진 이 핸들러 인스턴스는 이번 연결 하나에만 사용합니다.
        EchoClientHandler handler = new EchoClientHandler(request);
        // 클라이언트는 연결 수락용 그룹 없이 하나의 NIO 그룹으로 I/O를 처리합니다.
        EventLoopGroup group =
                new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        // 연결 시도 중 실패해도 생성된 채널을 finally에서 닫을 수 있도록 보관합니다.
        Channel channel = null;

        try {
            // 클라이언트가 시작할 연결의 설정을 모읍니다.
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    // 대기 소켓이 아닌 연결별 TCP 소켓 채널을 사용합니다.
                    .channel(NioSocketChannel.class)
                    // 연결 수립의 제한 시간은 밀리초 단위로 지정합니다.
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 5초 동안 읽기가 없으면 연결을 닫습니다. 요청 전체의 5초 마감 시간은 아닙니다.
                            ch.pipeline().addLast(new ReadTimeoutHandler(5));
                            // 시간 제한 핸들러 다음에 응답 누적과 검증을 담당할 핸들러를 추가합니다.
                            ch.pipeline().addLast(handler);
                        }
                    });

            // 서버에 비동기 연결을 요청하고 결과를 나타내는 Future를 받습니다.
            ChannelFuture connectFuture = bootstrap.connect("127.0.0.1", 8000);
            channel = connectFuture.channel();
            // main 스레드에서 연결 시도의 완료를 기다리고 실패를 확인합니다.
            connectFuture.sync();
            // 에코 처리나 오류로 채널이 닫힐 때까지 main 스레드에서 기다립니다.
            channel.closeFuture().sync();

            // 연결 종료와 에코 성공은 다르므로 핸들러의 검증 결과도 확인합니다.
            if (!handler.isSuccess()) {
                throw new IllegalStateException("에코 응답을 정상적으로 완료하지 못했습니다.");
            }
        } finally {
            // 실패로 빠져나온 경우에도 남아 있는 채널의 종료를 요청하고 완료를 기다립니다.
            if (channel != null) {
                channel.close().syncUninterruptibly();
            }
            // 채널과 별개로 이벤트 루프 그룹도 종료하고 완료를 확인합니다.
            group.shutdownGracefully().syncUninterruptibly();
        }
        System.out.println("클라이언트를 종료했습니다.");
    }
}
