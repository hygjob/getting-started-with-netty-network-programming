package example.netty.echo;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

// 일반 작업과 예약 작업이 같은 이벤트 루프에서 실행되는 모습을 관찰합니다.
public final class EventLoopTasksDemo {
    public static void main(String[] args) throws Exception {
        EventLoopGroup group =
                new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            // 그룹에서 이벤트 루프 하나를 선택해 예제 작업을 제출할 수 있게 합니다.
            EventLoop loop = group.next();
            // 벽시계 시각이 아닌 경과 시간 측정용 기준값을 저장합니다.
            long started = System.nanoTime();
            // 작업을 제출하며, 호출한 main 스레드에서 직접 실행하는 것은 아닙니다.
            loop.execute(() -> print("일반 작업", started));
            // 1초 간격을 목표로 예약합니다. 루프가 바쁘면 실제 실행은 늦어질 수 있습니다.
            ScheduledFuture<?> periodic = loop.scheduleAtFixedRate(
                    () -> print("주기 작업", started), 0, 1, TimeUnit.SECONDS);
            loop.schedule(() -> {
                // 주기 작업의 이후 실행을 취소합니다. 실행 중인 코드를 강제로 중단하지는 않습니다.
                periodic.cancel(false);
                print("주기 작업 취소", started);
            // 3.5초 뒤 취소 작업을 예약하고, main에서 그 작업의 완료를 기다립니다.
            }, 3500, TimeUnit.MILLISECONDS).sync();
        } finally {
            // 작업 관찰을 마친 뒤 실행 자원의 종료까지 확인합니다.
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static void print(String label, long started) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.println(label + " elapsedMs=" + elapsed
                + " thread=" + Thread.currentThread().getName());
    }
}
