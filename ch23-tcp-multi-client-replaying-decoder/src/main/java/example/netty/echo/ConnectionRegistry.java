package example.netty.echo;

import io.netty.channel.Channel;
import java.util.concurrent.atomic.AtomicInteger;

// 서로 다른 worker EventLoop에서 실행될 수 있는 연결 수를 안전하게 셉니다.
public final class ConnectionRegistry {
    private final AtomicInteger activeConnections = new AtomicInteger();

    public void connected(Channel channel) {
        int count = activeConnections.incrementAndGet();
        System.out.printf("[연결] id=%s remote=%s active=%d%n",
                channel.id().asShortText(), channel.remoteAddress(), count);
    }

    public void disconnected(Channel channel) {
        int count = activeConnections.decrementAndGet();
        System.out.printf("[해제] id=%s remote=%s active=%d%n",
                channel.id().asShortText(), channel.remoteAddress(), count);
    }

    public int size() {
        return activeConnections.get();
    }
}
