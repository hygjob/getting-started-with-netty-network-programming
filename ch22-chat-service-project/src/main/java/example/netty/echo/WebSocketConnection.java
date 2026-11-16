package example.netty.echo;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

// ChatConnection을 WebSocket 채널에 연결하고 느린 사용자 정책을 적용합니다.
public final class WebSocketConnection implements ChatConnection {
    private static final System.Logger LOG = System.getLogger(WebSocketConnection.class.getName());
    private final Channel channel;
    private final String user;
    private final long slowNanos;
    private final LongAdder dropped;
    // 아래 상태는 서비스 실행기 한 스레드에서만 읽고 씁니다.
    private boolean slow;
    private long slowSince;
    private int droppedHere;

    public WebSocketConnection(Channel channel, String user, long slowMillis, LongAdder dropped) {
        this.channel = channel;
        this.user = user;
        this.slowNanos = TimeUnit.MILLISECONDS.toNanos(slowMillis);
        this.dropped = dropped;
    }

    @Override
    public String id() { return channel.id().asShortText(); }

    @Override
    public String user() { return user; }

    @Override
    public boolean send(String line) {
        if (!channel.isActive()) return false;
        if (!channel.isWritable()) {
            // 송신 대기량이 높은 수위를 넘었습니다. 더 쌓지 않고 이 메시지를 버립니다.
            dropped.increment();
            droppedHere++;
            long now = System.nanoTime();
            if (!slow) {
                slow = true;
                slowSince = now;
            } else if (now - slowSince > slowNanos) {
                LOG.log(System.Logger.Level.WARNING, "event=slow_client_closed channel=" + id()
                        + " user=" + user + " dropped=" + droppedHere);
                // Close 프레임도 밀린 데이터 뒤에 줄을 서므로 TCP 연결을 바로 닫습니다.
                channel.close();
            }
            return false;
        }
        slow = false;
        if (droppedHere > 0) {
            // 버린 메시지 수를 먼저 알려 클라이언트가 빠진 구간을 인식하게 합니다.
            write("NOTICE dropped " + droppedHere);
            droppedHere = 0;
        }
        write(line);
        return true;
    }

    private void write(String line) {
        channel.writeAndFlush(new TextWebSocketFrame(line))
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    @Override
    public void close(int code, String reason) { closeInLoop(channel, code, reason); }

    // Close 프레임을 보내고 쓰기가 끝나는 즉시 연결을 닫습니다. 다른 스레드에서 리스너를 늦게 붙이면
    // 그 사이 도착한 상대의 Close 응답을 프로토콜 핸들러가 먼저 처리할 수 있으므로 이벤트 루프 안에서 실행합니다.
    static void closeInLoop(Channel channel, int code, String reason) {
        channel.eventLoop().execute(() -> channel.writeAndFlush(new CloseWebSocketFrame(code, reason))
                .addListener(ChannelFutureListener.CLOSE));
    }
}
