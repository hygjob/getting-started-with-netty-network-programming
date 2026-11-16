package example.netty.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// 연결 수, 채널별 업무 수, 공유 큐, 쓰기 대기를 각각 제한하는 에코 서버입니다.
public final class BoundedEchoServer {
    public static void main(String[] args) throws Exception {
        // 큐가 가득 차면 이벤트 루프가 업무를 대신 실행하지 않도록 AbortPolicy를 사용합니다.
        ThreadPoolExecutor jobs = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
        AtomicInteger connections = new AtomicInteger();
        ChannelGroup clients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
        EventLoopGroup acceptor = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workers = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        Channel server = null;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap().group(acceptor, workers)
                    .channel(NioServerSocketChannel.class)
                    // 워터마크는 쓰기 가능 알림 기준이며 강제 메모리 상한은 아닙니다.
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                            new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            WireProtocol.install(ch.pipeline());
                            ch.pipeline().addLast(new Handler(jobs, connections, clients));
                        }
                    });
            ChannelFuture bind = bootstrap.bind("127.0.0.1", 8000);
            server = bind.channel();
            bind.sync();
            System.out.println("용량 제한 에코 서버 시작. Enter로 종료합니다.");
            System.in.read();
        } finally {
            if (server != null) server.close().syncUninterruptibly();
            clients.close().awaitUninterruptibly();
            jobs.shutdownNow();
            jobs.awaitTermination(3, TimeUnit.SECONDS);
            workers.shutdownGracefully();
            acceptor.shutdownGracefully();
            workers.terminationFuture().syncUninterruptibly();
            acceptor.terminationFuture().syncUninterruptibly();
        }
    }

    // 채널별 진행 중 요청과 쓰기 가능 상태를 같은 이벤트 루프에서 갱신합니다.
    private static final class Handler extends SimpleChannelInboundHandler<WireMessage> {
        private final ThreadPoolExecutor jobs;
        private final AtomicInteger connections;
        private final ChannelGroup clients;
        private boolean counted;
        private int inFlight;
        private ScheduledFuture<?> slowTimer;

        Handler(ThreadPoolExecutor jobs, AtomicInteger connections, ChannelGroup clients) {
            this.jobs = jobs;
            this.connections = connections;
            this.clients = clients;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            // 연결 이벤트는 여러 루프에서 발생할 수 있으므로 카운터는 AtomicInteger로 관리합니다.
            if (connections.incrementAndGet() > 100) {
                connections.decrementAndGet();
                ctx.close();
                return;
            }
            counted = true;
            clients.add(ctx.channel());
            ctx.fireChannelActive();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WireMessage message) {
            if (!ctx.channel().isActive()) return;
            if (message.type() != WireMessage.REQUEST || inFlight >= 8) {
                // 이미 읽은 바이트에서 추가 메시지가 나올 수 있으므로 강제 한도를 둡니다.
                ctx.close();
                return;
            }
            inFlight++;
            updateRead(ctx);
            try {
                jobs.execute(() -> {
                    boolean interrupted = false;
                    try {
                        Thread.sleep(100); // 업무 스레드에서만 느린 작업을 흉내 냅니다.
                    } catch (InterruptedException cause) {
                        Thread.currentThread().interrupt();
                        interrupted = true;
                    }
                    final boolean failed = interrupted;
                    try {
                        ctx.executor().execute(() -> complete(ctx, message, failed));
                    } catch (RejectedExecutionException ignored) {
                        // 실행기가 종료되었습니다. 보관한 값은 record와 String뿐입니다.
                    }
                });
            } catch (RejectedExecutionException cause) {
                inFlight--;
                if (ctx.channel().isWritable()) {
                    ctx.writeAndFlush(new WireMessage(WireMessage.ERROR, message.requestId(), "busy"))
                            .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                } else {
                    ctx.close();
                }
                updateRead(ctx);
            }
        }

        private void complete(ChannelHandlerContext ctx, WireMessage request, boolean failed) {
            inFlight--;
            if (ctx.channel().isActive()) {
                ctx.writeAndFlush(new WireMessage(failed ? WireMessage.ERROR : WireMessage.RESPONSE,
                        request.requestId(), failed ? "interrupted" : request.body()))
                        .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                updateRead(ctx);
            }
        }

        private void updateRead(ChannelHandlerContext ctx) {
            if (ctx.channel().isActive()) {
                // 업무 수와 쓰기 가능 상태가 모두 해소되어야 자동 읽기를 재개합니다.
                ctx.channel().config().setAutoRead(inFlight < 4 && ctx.channel().isWritable());
            }
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            if (ctx.channel().isWritable()) {
                if (slowTimer != null) { slowTimer.cancel(false); slowTimer = null; }
            } else if (slowTimer == null) {
                slowTimer = ctx.executor().schedule(() -> {
                    slowTimer = null;
                    if (!ctx.channel().isWritable()) ctx.close();
                }, 3, TimeUnit.SECONDS);
            }
            updateRead(ctx);
            ctx.fireChannelWritabilityChanged();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (slowTimer != null) slowTimer.cancel(false);
            if (counted) { counted = false; connections.decrementAndGet(); }
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println(cause);
            ctx.close();
        }
    }
}
