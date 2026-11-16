package example.netty.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

// 연결 시도, 현재 채널, 재시도 예약을 한 이벤트 루프에서 상태로 관리합니다.
public final class ReconnectingClient {
    private final EventLoop owner;
    private final Bootstrap bootstrap;
    private boolean stopped;
    private boolean connecting;
    private Channel current;
    private Channel pendingChannel;
    private ScheduledFuture<?> retry;
    private int failures;
    private long generation;
    private long connectedAt;

    private ReconnectingClient(EventLoop owner) {
        this.owner = owner;
        // 단일 EventLoop를 전달해 연결 콜백과 상태 변경이 같은 스레드에서 처리하게 합니다.
        bootstrap = new Bootstrap().group(owner)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        WireProtocol.install(ch.pipeline());
                        ch.pipeline().addLast(new IdleStateHandler(0, 5, 0));
                        ch.pipeline().addLast(new HeartbeatHandler(() -> onHealthy(ch)));
                    }
                });
    }

    private void connect() {
        if (stopped || connecting || current != null) return;
        connecting = true;
        long ticket = ++generation;
        ChannelFuture future = bootstrap.connect("127.0.0.1", 8000);
        pendingChannel = future.channel();
        future.addListener((ChannelFutureListener) result -> {
            Channel candidate = result.channel();
            // 중지하거나 generation이 바뀐후 도착한 오래된 콜백은 현재 연결을 바꾸지 않습니다.
            if (stopped || ticket != generation) {
                candidate.close();
                return;
            }
            connecting = false;
            pendingChannel = null;
            if (!result.isSuccess()) {
                System.out.println("연결 실패: " + result.cause());
                candidate.close();
                scheduleRetry();
                return;
            }
            current = candidate;
            connectedAt = System.nanoTime();
            System.out.println("TCP 연결 성공: " + candidate.id());
            // 연결 성공 이후의 종료는 connect Future가 아니라 closeFuture로 관찰합니다.
            candidate.closeFuture().addListener(f -> {
                if (ticket == generation && current == candidate) {
                    current = null;
                    System.out.println("연결 종료 감지");
                    scheduleRetry();
                }
            });
        });
    }

    private void onHealthy(Channel channel) {
        if (current == channel && System.nanoTime() - connectedAt >= TimeUnit.SECONDS.toNanos(10)) {
            // TCP 연결 직후가 아니라 안정적으로 PONG을 받은 뒤 초기화합니다.
            failures = 0;
        }
    }

    private void scheduleRetry() {
        if (stopped || retry != null) return;
        long cap = Math.min(30_000L, 500L << failures);
        failures = Math.min(failures + 1, 6);
        // 상한은 늘리되 실제 대기는 그 범위에서 무작위로 고릅니다.
        long delay = ThreadLocalRandom.current().nextLong(cap + 1);
        System.out.println(delay + "ms 뒤 재연결 (상한 " + cap + "ms)");
        retry = owner.schedule(() -> {
            retry = null;
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void stop() {
        stopped = true;
        generation++;
        if (retry != null) {
            retry.cancel(false);
            retry = null;
        }
        // 연결 시도 중인 소켓과 현재 채널을 모두 닫습니다.
        if (pendingChannel != null) pendingChannel.close();
        if (current != null) current.close();
    }

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(
                1, NioIoHandler.newFactory());
        EventLoop owner = group.next();
        ReconnectingClient client = new ReconnectingClient(owner);
        try {
            owner.submit(client::connect).sync();
            System.out.println("재연결 클라이언트 시작. Enter: 종료");
            System.in.read();
        } finally {
            owner.submit(client::stop).syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }
}
