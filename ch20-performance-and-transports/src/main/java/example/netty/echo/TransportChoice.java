package example.netty.echo;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;

// IoHandlerFactory와 서버 채널 구현을 같은 트랜스포트 조합으로 묶습니다.
public record TransportChoice(String name, IoHandlerFactory factory,
                              Class<? extends ServerChannel> serverType) {
    public static TransportChoice select(String requested) {
        String mode = requested;
        if (mode.equals("auto")) {
            mode = Epoll.isAvailable() ? "epoll" : KQueue.isAvailable() ? "kqueue" : "nio";
            System.out.println("auto 선택: " + mode);
        }
        return switch (mode) {
            case "nio" -> new TransportChoice("nio", NioIoHandler.newFactory(),
                    NioServerSocketChannel.class);
            case "epoll" -> {
                // 명시적으로 요청한 트랜스포트는 사용할 수 없을 때 조용히 대체하지 않습니다.
                if (!Epoll.isAvailable()) throw new IllegalStateException(
                        "epoll을 사용할 수 없습니다.", Epoll.unavailabilityCause());
                yield new TransportChoice("epoll", EpollIoHandler.newFactory(),
                        EpollServerSocketChannel.class);
            }
            case "kqueue" -> {
                if (!KQueue.isAvailable()) throw new IllegalStateException(
                        "kqueue를 사용할 수 없습니다.", KQueue.unavailabilityCause());
                yield new TransportChoice("kqueue", KQueueIoHandler.newFactory(),
                        KQueueServerSocketChannel.class);
            }
            case "uring" -> {
                if (!IoUring.isAvailable()) throw new IllegalStateException(
                        "io_uring을 사용할 수 없습니다.", IoUring.unavailabilityCause());
                yield new TransportChoice("uring", IoUringIoHandler.newFactory(),
                        IoUringServerSocketChannel.class);
            }
            default -> throw new IllegalArgumentException("nio, epoll, kqueue, uring, auto 중 선택하십시오.");
        };
    }
}
