package example.netty.echo;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class PromiseDemo {
    public static void main(String[] args) throws Exception {
        int input = args.length == 0 ? 21 : Integer.parseInt(args[0]);
        // 계산 익스큐터와 분리해 완료 리스너의 실행을 담당할 익스큐터를 준비합니다.
        DefaultEventExecutor notifications = new DefaultEventExecutor();
        // 계산은 별도 작업 스레드에 맡기며 try가 끝날 때 익스큐터를 정리합니다.
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<Integer> result = twice(notifications, worker, input);
            // 성공 값 또는 실패 원인은 완료 이후 리스너에서 확인합니다.
            result.addListener(done -> {
                if (done.isSuccess()) {
                    System.out.println("계산 결과=" + done.getNow()
                            + " listenerThread=" + Thread.currentThread().getName());
                } else {
                    System.err.println("계산 실패=" + done.cause());
                }
            });
            // main에서 계산 완료를 기다립니다. 리스너 안에서 같은 방식으로 기다리지는 않습니다.
            result.sync();
        } finally {
            notifications.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static Future<Integer> twice(EventExecutor notifications,
                                          Executor worker, int input) {
        // 생산자는 Promise에 결과를 기록하고 호출자는 Future로 결과를 관찰합니다.
        Promise<Integer> promise = notifications.newPromise();
        try {
            // 실제 계산을 작업 익스큐터에 제출합니다.
            worker.execute(() -> {
                try {
                    // 정수 범위를 넘으면 예외가 발생하므로 실패 결과도 확인할 수 있습니다.
                    int value = Math.multiplyExact(input, 2);
                    // 아직 미완료일 때만 성공으로 완료합니다.
                    promise.trySuccess(value);
                } catch (ArithmeticException e) {
                    // 계산 오류 또는 작업 제출 거부를 같은 비동기 실패 경로로 전달합니다.
                    promise.tryFailure(e);
                }
            });
        } catch (RejectedExecutionException e) {
            // 계산 오류 또는 작업 제출 거부를 같은 비동기 실패 경로로 전달합니다.
            promise.tryFailure(e);
        }
        // 완료를 기다리지 않고 결과 관찰용 Future를 반환합니다.
        return promise;
    }
}
