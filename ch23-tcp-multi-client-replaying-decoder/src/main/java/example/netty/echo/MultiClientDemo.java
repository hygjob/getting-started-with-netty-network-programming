package example.netty.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Promise;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

// 하나의 JVM에서 독립적인 TCP 연결 여러 개를 열어 단일 서버를 관찰합니다.
public final class MultiClientDemo {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = MultiClientBusinessServer.DEFAULT_PORT;

    public static void main(String[] args) throws Exception {
        int clientCount = args.length == 0 ? 3 : parseClientCount(args[0]);
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(
                2, NioIoHandler.newFactory());
        List<ClientSession> sessions = new ArrayList<>();

        try {
            for (int number = 1; number <= clientCount; number++) {
                long requestId = number;
                String requestBody = requestFor(number);
                Promise<WireMessage> reply = group.next().newPromise();
                Bootstrap bootstrap = new Bootstrap()
                        .group(group)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel channel) {
                                channel.pipeline().addLast("replayDecoder", new ReplayWireDecoder());
                                channel.pipeline().addLast("encoder", new WireEncoder());
                                channel.pipeline().addLast("reply", new ReplyHandler(requestId, reply));
                            }
                        });
                Channel channel = bootstrap.connect(HOST, PORT).sync().channel();
                sessions.add(new ClientSession(number, requestId, requestBody, channel, reply));
            }

            // 모두 연결한 뒤 서로 다른 길이와 업무를 가진 요청을 보냅니다.
            for (ClientSession session : sessions) {
                int requestBytes = WireProtocol.encodeBody(session.requestBody()).length;
                System.out.printf("[클라이언트 %d] 요청 바이트=%d command=%s%n",
                        session.number(), requestBytes, commandOf(session.requestBody()));
                session.channel().writeAndFlush(new WireMessage(
                        WireMessage.REQUEST, session.requestId(), session.requestBody()))
                        .addListener(future -> {
                            if (!future.isSuccess()) {
                                session.reply().tryFailure(future.cause());
                            }
                        });
            }

            for (ClientSession session : sessions) {
                WireMessage response = session.reply().get(5, TimeUnit.SECONDS);
                String kind = response.type() == WireMessage.RESPONSE ? "결과" : "업무 오류";
                System.out.printf("[클라이언트 %d] %s: %s%n",
                        session.number(), kind, response.body());
            }
        } finally {
            for (ClientSession session : sessions) {
                session.channel().close().syncUninterruptibly();
            }
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static int parseClientCount(String value) {
        int count = Integer.parseInt(value);
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("클라이언트 수는 1부터 100 사이여야 합니다.");
        }
        return count;
    }

    private static String requestFor(int clientNumber) {
        // 클라이언트 번호 자체가 아니라 반복 횟수로 본문 바이트 수를 변경합니다.
        int repeatCount = 4 + Math.floorMod(clientNumber * 7, 40);
        return switch (Math.floorMod(clientNumber - 1, 3)) {
            case 0 -> "UPPER " + "netty-tcp/".repeat(repeatCount);
            case 1 -> "REVERSE " + "가변길이-".repeat(repeatCount);
            case 2 -> "SUM " + numbersForSum(repeatCount);
            default -> throw new Error("도달할 수 없는 업무 선택");
        };
    }

    private static String numbersForSum(int count) {
        StringBuilder values = new StringBuilder();
        for (int number = 1; number <= count; number++) {
            if (number > 1) values.append(' ');
            values.append((long) number * 10);
        }
        return values.toString();
    }

    private static String commandOf(String requestBody) {
        int separator = requestBody.indexOf(' ');
        return separator < 0 ? requestBody : requestBody.substring(0, separator);
    }

    private record ClientSession(int number, long requestId, String requestBody, Channel channel,
                                 Promise<WireMessage> reply) { }

    private static final class ReplyHandler
            extends SimpleChannelInboundHandler<WireMessage> {
        private final long expectedRequestId;
        private final Promise<WireMessage> reply;

        ReplyHandler(long expectedRequestId, Promise<WireMessage> reply) {
            this.expectedRequestId = expectedRequestId;
            this.reply = reply;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WireMessage message) {
            if ((message.type() == WireMessage.RESPONSE || message.type() == WireMessage.ERROR)
                    && message.requestId() == expectedRequestId) {
                reply.trySuccess(message);
                return;
            }
            reply.tryFailure(new IllegalStateException("예상하지 못한 응답: " + message));
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            reply.tryFailure(new ClosedChannelException());
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            reply.tryFailure(cause);
            ctx.close();
        }
    }
}
