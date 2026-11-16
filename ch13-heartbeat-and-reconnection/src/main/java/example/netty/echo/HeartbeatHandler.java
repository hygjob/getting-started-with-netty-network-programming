package example.netty.echo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// 연결별 PING/PONG 대기와 제한 시간을 관리합니다. 여러 채널에서 공유하지 않습니다.
public final class HeartbeatHandler extends SimpleChannelInboundHandler<WireMessage> {
    private final Runnable onHealthy;
    private long sequence;
    private long waitingId;
    private ScheduledFuture<?> deadline;

    public HeartbeatHandler(Runnable onHealthy) {
        this.onHealthy = onHealthy;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        sendPing(ctx);
        ctx.fireChannelActive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof IdleStateEvent idle
                && idle.state() == IdleState.WRITER_IDLE) {
            // 이미 응답을 기다리는 PING이 있으면 새 확인을 보내지 않습니다.
            if (waitingId == 0) sendPing(ctx);
        } else {
            ctx.fireUserEventTriggered(event);
        }
    }

    private void sendPing(ChannelHandlerContext ctx) {
        if (!ctx.channel().isActive()) return;
        if (sequence == Long.MAX_VALUE) {
            ctx.close();
            return;
        }
        long id = ++sequence;
        waitingId = id;
        // 쓰기가 끝나기를 무한히 기다리지 않도록 전송 요청 전에 시작합니다.
        deadline = ctx.executor().schedule(() -> {
            if (waitingId == id) {
                System.out.println("PONG 제한 시간 초과: " + id);
                ctx.close();
            }
        }, 3, TimeUnit.SECONDS);
        System.out.println("PING " + id);
        ctx.writeAndFlush(new WireMessage(WireMessage.PING, id, ""))
                .addListener(f -> {
                    if (!f.isSuccess()) ctx.close();
                });
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WireMessage msg) {
        if (msg.type() == WireMessage.PONG) {
            if (waitingId != 0 && msg.requestId() == waitingId) {
                clearDeadline();
                System.out.println("PONG " + msg.requestId());
                onHealthy.run();
            } else {
                System.out.println("대기 중인 PING과 맞지 않는 PONG 무시");
            }
        } else {
            // WireMessage는 참조 카운팅 객체가 아니므로 retain이 필요 없습니다.
            ctx.fireChannelRead(msg);
        }
    }

    private void clearDeadline() {
        waitingId = 0;
        if (deadline != null) {
            deadline.cancel(false);
            deadline = null;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        clearDeadline();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        clearDeadline();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("하트비트 연결 오류: " + cause);
        ctx.close();
    }
}
