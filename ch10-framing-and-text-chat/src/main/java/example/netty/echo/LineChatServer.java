package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.nio.charset.StandardCharsets;

// 줄바꿈으로 메시지를 구분하고 접속한 사용자에게 같은 문자열을 브로드캐스트합니다.
public final class LineChatServer {
    public static void main(String[] args) throws Exception {
        EventLoopGroup acceptors = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workers = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        // 서버 전체에서 하나의 그룹을 공유합니다. 연결마다 새로 만들지 않습니다.
        ChannelGroup members = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
        Channel server = null;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(acceptors, workers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 먼저 줄 경계를 찾고, 그다음 완성된 줄을 UTF-8 문자열로 바꿉니다.
                            ch.pipeline().addLast(new LineBasedFrameDecoder(1024, true, true));
                            ch.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                            // 송신 문자열은 인코더를 거쳐 바이트가 됩니다. 줄바꿈은 붙이지 않습니다.
                            ch.pipeline().addLast(new StringEncoder(StandardCharsets.UTF_8));
                            ch.pipeline().addLast(new ChatHandler(members));
                        }
                    });
            ChannelFuture binding = bootstrap.bind("127.0.0.1", 8000);
            server = binding.channel();
            binding.sync();
            System.out.println("채팅 서버가 준비되었습니다. Enter로 종료합니다.");
            System.in.read();
        } finally {
            if (server != null) {
                // 새 연결 수락을 먼저 중지합니다.
                server.close().syncUninterruptibly();
            }
            // 이미 접속한 사용자 연결을 닫은 뒤 이벤트 루프를 종료합니다.
            members.close().awaitUninterruptibly();
            acceptors.shutdownGracefully();
            workers.shutdownGracefully();
            acceptors.terminationFuture().syncUninterruptibly();
            workers.terminationFuture().syncUninterruptibly();
        }
    }

    // 연결마다 새로 만들고, 공유 그룹만 참조합니다.
    private static final class ChatHandler extends SimpleChannelInboundHandler<String> {
        private final ChannelGroup members;

        private ChatHandler(ChannelGroup members) {
            this.members = members;
        }

        // 그룹에 먼저 추가하므로 새 사용자도 자신의 입장 알림을 받습니다.
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            members.add(ctx.channel());
            broadcast("JOIN " + ctx.channel().id().asShortText() + "\n");
            ctx.fireChannelActive();
        }

        // 프레임 한도와 업무 본문 한도를 따로 적용합니다.
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String message) {
            if (message.isBlank() || message.getBytes(StandardCharsets.UTF_8).length > 512) {
                ctx.writeAndFlush("안내: 내용은 본문 내용이 있어야 하고 512바이트까지 허용합니다.\n")
                        .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                return;
            }
            // 송신 문자열에 줄바꿈을 명시합니다. StringEncoder는 구분자를 붙이지 않습니다.
            broadcast("[" + ctx.channel().id().asShortText() + "] " + message + "\n");
        }

        // 닫힌 채널의 자동 제거가 있어도 브로드캐스트 대상에서 빼는 의도를 보여줍니다.
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            members.remove(ctx.channel());
            broadcast("LEAVE " + ctx.channel().id().asShortText() + "\n");
            ctx.fireChannelInactive();
        }

        private void broadcast(String message) {
            ChannelGroupFuture writes = members.writeAndFlush(message);
            writes.addListener(done -> {
                // 일부 수신자의 실패가 다른 수신자의 성공을 취소하는 것은 아닙니다.
                for (ChannelFuture write : writes) {
                    if (!write.isSuccess()) {
                        write.channel().close();
                    }
                }
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("채팅 연결 오류: " + cause);
            ctx.close();
        }
    }
}
