package example.netty.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetSocketAddress;
import java.util.concurrent.ThreadLocalRandom;

// 로컬 포트에 bind한 뒤 데이터그램마다 목적지를 넣어 보냅니다.
public final class UdpStatusSender {
    public static void main(String[] args) throws Exception {
        int deviceId = args.length == 0 ? 1 : Integer.parseInt(args[0]);
        boolean disorder = args.length > 1 && args[1].equals("disorder");
        if (deviceId <= 0) throw new IllegalArgumentException("장치 ID는 양수입니다.");
        int[] sequences = disorder ? new int[]{1, 3, 2, 3, 4} : new int[]{1, 2, 3, 4, 5};
        // 재시작 후에는 이전 송신자의 과거 보고로 오인하지 않도록 실행 ID를 바꿉니다.
        long bootId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        InetSocketAddress target = new InetSocketAddress("127.0.0.1", 9999);
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Channel channel = null;
        try {
            Bootstrap bootstrap = new Bootstrap().group(group)
                    .channelFactory(() -> new NioDatagramChannel(SocketProtocolFamily.INET))
                    .handler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            System.err.println(cause);
                            ctx.close();
                        }
                    });
            ChannelFuture bind = bootstrap.bind("127.0.0.1", 0);
            channel = bind.channel();
            bind.sync();
            System.out.println("송신 소켓: " + channel.localAddress());
            for (int sequence : sequences) {
                StatusReport report = new StatusReport(deviceId, bootId, sequence,
                        ThreadLocalRandom.current().nextInt(101));
                channel.writeAndFlush(new DatagramPacket(report.encode(channel.alloc()), target)).sync();
                System.out.println("로컬 송신 완료: " + sequence);
                Thread.sleep(1000); // main 스레드에서만 sleep을 둡니다.
            }
        } finally {
            if (channel != null) channel.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }
}
