package example.netty.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

// 콘솔에서 한 줄을 읽어 서버에 보내고, 서버가 브로드캐스트한 줄을 화면에 출력합니다.
public final class LineChatClient {
    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Channel channel = null;
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group).channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 서버와 같은 줄 경계·UTF-8 규약을 사용합니다.
                            ch.pipeline().addLast(new LineBasedFrameDecoder(1024, true, true));
                            ch.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                            ch.pipeline().addLast(new StringEncoder(StandardCharsets.UTF_8));
                            ch.pipeline().addLast(new DisplayHandler());
                        }
                    });
            ChannelFuture connecting = bootstrap.connect("127.0.0.1", 8000);
            channel = connecting.channel();
            connecting.sync();
            System.out.println("한 줄씩 입력합니다. /quit 또는 입력 종료로 끝냅니다.");
            BufferedReader console = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            // 키보드 입력과 쓰기 대기는 메인 스레드에서만 수행합니다.
            while (channel.isActive() && (line = console.readLine()) != null) {
                if ("/quit".equals(line)) {
                    break;
                }
                if (line.getBytes(StandardCharsets.UTF_8).length > 512) {
                    System.out.println("512바이트 이하로 입력하시기 바랍니다.");
                    continue;
                }
                // 수신자가 줄의 끝을 찾을 수 있도록 구분자를 붙입니다.
                channel.writeAndFlush(line + "\n").sync();
            }
        } finally {
            if (channel != null) {
                channel.close().syncUninterruptibly();
            }
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    // 서버가 보낸 완성된 한 줄을 그대로 출력합니다.
    private static final class DisplayHandler extends SimpleChannelInboundHandler<String> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String message) {
            System.out.println(message);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            System.out.println("서버 연결이 종료되었습니다. 입력 대기 중이라면 Enter를 누르십시오.");
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("채팅 클라이언트 오류: " + cause);
            ctx.close();
        }
    }
}
