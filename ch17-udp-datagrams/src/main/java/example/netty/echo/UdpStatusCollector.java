package example.netty.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// UDP 소켓 하나로 여러 송신자의 최신 상태만 보관합니다.
public final class UdpStatusCollector {
    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Channel channel = null;
        try {
            // UDP는 ServerBootstrap과 자식 연결이 없습니다. Bootstrap과 채널을 사용합니다.
            Bootstrap bootstrap = new Bootstrap().group(group)
                    .channelFactory(() -> new NioDatagramChannel(SocketProtocolFamily.INET))
                    .option(ChannelOption.RECVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(2048))
                    .option(ChannelOption.SO_RCVBUF, 64 * 1024)
                    .handler(new CollectorHandler());
            ChannelFuture bind = bootstrap.bind("127.0.0.1", 9999);
            channel = bind.channel();
            bind.sync();
            System.out.println("UDP 수집기 시작: " + channel.localAddress() + ". Enter 종료");
            System.in.read();
        } finally {
            if (channel != null) channel.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    // 발신 주소는 인증용이 아닙니다. 세션 키의 일부로만 사용합니다.
    private record SenderKey(InetSocketAddress address, int deviceId, long bootId) { }
    private record Latest(int sequence, int loadPercent, long receivedAt) { }

    private static final class CollectorHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        private final Map<SenderKey, Latest> latest = new HashMap<>(); // 송신자별 최신 상태를 보관합니다.
        private ScheduledFuture<?> cleanup;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            cleanup = ctx.executor().scheduleAtFixedRate(() -> {
                long now = System.nanoTime();
                latest.entrySet().removeIf(entry -> now - entry.getValue().receivedAt()
                        > TimeUnit.SECONDS.toNanos(30));
            }, 5, 5, TimeUnit.SECONDS);
            ctx.fireChannelActive();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            StatusReport report;
            try {
                report = StatusReport.decode(packet.content());
            } catch (IllegalArgumentException cause) {
                System.out.println("잘못된 데이터그램 폐기: " + packet.sender());
                return; // 다른 송신자도 사용하는 수신 채널은 계속 열어 둡니다.
            }
            SenderKey key = new SenderKey(packet.sender(), report.deviceId(), report.bootId());
            Latest old = latest.get(key);
            // 최신 상태는 도착 시각이 아니라 보고 번호로 판단합니다.
            if (old != null && report.sequence() <= old.sequence()) {
                System.out.println("중복 또는 과거 보고 폐기: " + report.sequence());
                return;
            }
            if (old == null && latest.size() >= 1024) {
                System.out.println("송신 세션 수 한도 초과: 새 세션 폐기");
                return;
            }
            if (old != null && report.sequence() > old.sequence() + 1L) {
                System.out.println("보고 번호 간격 발견: " + old.sequence() + " → " + report.sequence());
            }
            latest.put(key, new Latest(report.sequence(), report.loadPercent(), System.nanoTime()));
            System.out.println("발신=" + packet.sender() + " 장치=" + report.deviceId()
                    + " 실행=" + report.bootId() + " 번호=" + report.sequence()
                    + " 부하=" + report.loadPercent() + "%");
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (cleanup != null) cleanup.cancel(false);
            latest.clear();
            ctx.fireChannelInactive();
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            if (cleanup != null) cleanup.cancel(false);
            latest.clear();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("UDP 채널 오류: " + cause);
            ctx.close();
        }
    }
}
