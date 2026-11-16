package example.netty.echo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// 연결마다 새로 만들고, 요청 ID와 Promise를 같은 이벤트 루프에서 관리합니다.
public final class RequestSession extends SimpleChannelInboundHandler<WireMessage> {
    private static final int MAX_PENDING = 128;
    private final Map<Long, Pending> pending = new HashMap<>();
    // volatile은 외부 호출자가 등록된 컨텍스트를 볼 수 있게 합니다. 
    // 맵의 동시 접근을 허용하는 장치는 아닙니다.
    private volatile ChannelHandlerContext context;
    private long sequence;
    private boolean removed;

    // 결과 Promise와 취소할 타이머를 한 항목으로 묶습니다.
    private static final class Pending {
        final Promise<String> result;
        ScheduledFuture<?> timer;

        Pending(Promise<String> result) {
            this.result = result;
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        context = ctx;
    }

    public Future<String> request(String body, long timeoutMillis) {
        Objects.requireNonNull(body, "body");
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis");
        ChannelHandlerContext ctx = context;
        if (ctx == null) throw new IllegalStateException("파이프라인에 먼저 등록하십시오.");
        Promise<String> result = ctx.executor().newPromise();
        try {
            // 맵 변경은 채널 이벤트 루프에서만 수행합니다.
            ctx.executor().execute(() -> start(ctx, body, timeoutMillis, result));
        } catch (RejectedExecutionException cause) {
            result.tryFailure(cause);
        }
        return result;
    }

    private void start(ChannelHandlerContext ctx, String body, long timeoutMillis,
                       Promise<String> result) {
        if (result.isDone()) return;
        if (removed || !ctx.channel().isActive()) {
            result.tryFailure(new ClosedChannelException());
            return;
        }
        if (pending.size() >= MAX_PENDING || sequence == Long.MAX_VALUE) {
            result.tryFailure(new IllegalStateException("요청 수 또는 ID 한도 초과"));
            return;
        }
        long id = ++sequence;
        Pending entry = new Pending(result);
        // 응답이 빠르게 도착할 수 있으므로 송신 전에 대기 항목을 등록합니다.
        pending.put(id, entry);
        try {
            entry.timer = ctx.executor().schedule(() -> fail(id,
                    new TimeoutException("응답 제한 시간 초과: " + id)),
                    timeoutMillis, TimeUnit.MILLISECONDS);
            // 향후 취소될 때 정리하도록 리스너를 붙입니다. Promise는 이미 완료되었을 수 있습니다.
            result.addListener(f -> {                
                if (f.isCancelled()) take(id);
            });
            // 취소된 Promise는 이미 완료되었으므로 송신하지 않습니다.
            if (result.isCancelled()) {
                take(id);
                return;
            }
            ctx.writeAndFlush(new WireMessage(WireMessage.REQUEST, id, body))
                    .addListener(f -> {
                        if (!f.isSuccess()) fail(id, f.cause());
                    });
        } catch (RuntimeException cause) {
            fail(id, cause);
        }
    }

    // 성공·실패를 알리기 전에 항목을 제거하고 타이머를 취소합니다.
    private Pending take(long id) {
        Pending entry = pending.remove(id);
        if (entry != null && entry.timer != null) entry.timer.cancel(false);
        return entry;
    }

    private void fail(long id, Throwable cause) {
        Pending entry = take(id);
        if (entry != null) entry.result.tryFailure(cause);
    }

    private void failAll(Throwable cause) {
        // 맵을 먼저 비워 완료 리스너가 새 동작을 시작해도 순회 중 수정이 생기지 않게 합니다.
        var entries = new ArrayList<>(pending.values());
        pending.clear();
        for (Pending entry : entries) {
            if (entry.timer != null) entry.timer.cancel(false);
            entry.result.tryFailure(cause);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WireMessage msg) {
        if (msg.type() != WireMessage.RESPONSE && msg.type() != WireMessage.ERROR) {
            failAll(new IllegalStateException("응답이 아닌 메시지를 받았습니다."));
            ctx.close();
            return;
        }
        Pending entry = take(msg.requestId());
        if (entry == null) {
            // 늦은 응답을 다른 Promise에 붙이지 않습니다.
            System.out.println("대기 항목 없는 응답 무시: " + msg.requestId());
            return;
        }
        if (msg.type() == WireMessage.RESPONSE) {
            entry.result.trySuccess(msg.body());
        } else {
            entry.result.tryFailure(new IllegalStateException("서버 오류: " + msg.body()));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        failAll(new ClosedChannelException());
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        removed = true;
        failAll(new ClosedChannelException());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        failAll(cause);
        ctx.close();
    }
}
