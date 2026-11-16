package example.netty.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;

// 한 연결에서 세 요청을 먼저 시작하고 도착 순서와 무관하게 결과를 관찰합니다.
public final class RpcDemoClient {
    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(
                1, NioIoHandler.newFactory());
        RequestSession session = new RequestSession();
        Channel channel = null;
        try {
            Bootstrap bootstrap = new Bootstrap().group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            WireProtocol.install(ch.pipeline());
                            ch.pipeline().addLast(session);
                        }
                    });
            ChannelFuture connect = bootstrap.connect("127.0.0.1", 8000);
            channel = connect.channel();
            connect.sync();
            Future<String> slow = session.request("slow", 3000);
            Future<String> fast = session.request("fast", 3000);
            Future<String> timeout = session.request("timeout", 100);
            report("slow", slow);
            report("fast", fast);
            report("timeout", timeout);
            // main 스레드에서만 기다립니다. 세 요청은 이미 모두 시작되었습니다.
            slow.await();
            fast.await();
            timeout.await();
        } finally {
            if (channel != null) channel.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static void report(String name, Future<String> result) {
        result.addListener(f -> {
            if (f.isSuccess()) {
                System.out.println(name + " 성공: " + f.getNow());
            } else {
                System.out.println(name + " 실패: " + f.cause());
            }
        });
    }
}
