package example.netty.echo;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import java.nio.charset.StandardCharsets;

// 전송 계층 핸들러입니다. WebSocket 프레임을 문자열 명령으로 바꾸어 서비스에 넘기고 연결 사건을 알립니다.
public final class ChatFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    public static final AttributeKey<String> USER = AttributeKey.valueOf("chat.user");
    private static final System.Logger LOG = System.getLogger(ChatFrameHandler.class.getName());
    private final ChatService service;
    private final ChatSettings settings;
    private final ChannelGroup channels;
    // 서비스 등록이 접수된 뒤에만 값이 있습니다. 이 채널의 이벤트 루프에서만 다룹니다.
    private WebSocketConnection connection;

    public ChatFrameHandler(ChatService service, ChatSettings settings, ChannelGroup channels) {
        this.service = service;
        this.settings = settings;
        this.channels = channels;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            WebSocketConnection created = new WebSocketConnection(ctx.channel(),
                    ctx.channel().attr(USER).get(), settings.slowMillis(), service.droppedCounter());
            // 종료 절차가 이 채널을 놓치지 않도록 서비스 등록보다 먼저 그룹에 넣습니다.
            channels.add(ctx.channel());
            if (!service.submit(() -> service.connected(created))) {
                // 1013(Try Again Later): 과부하나 종료 중이라 지금은 받을 수 없습니다.
                created.close(1013, "try again later");
                return;
            }
            connection = created;
            LOG.log(System.Logger.Level.INFO, "event=ws_open channel=" + created.id()
                    + " remote=" + ctx.channel().remoteAddress() + " user=" + created.user());
        } else if (event instanceof IdleStateEvent idle) {
            if (idle.state() == IdleState.READER_IDLE) {
                LOG.log(System.Logger.Level.INFO, "event=idle_closed channel=" + ctx.channel().id().asShortText());
                ctx.close();
            } else if (connection != null && idle.state() == IdleState.WRITER_IDLE) {
                ctx.writeAndFlush(new PingWebSocketFrame(Unpooled.copiedBuffer("hb", StandardCharsets.UTF_8)))
                        .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
        } else {
            ctx.fireUserEventTriggered(event);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (connection == null) return;
        if (!(frame instanceof TextWebSocketFrame text)) {
            ctx.writeAndFlush(new CloseWebSocketFrame(1003, "text only"))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        // 프레임은 이 메서드가 끝나면 해제되므로 서비스에는 복사한 문자열만 넘깁니다.
        String line = text.text();
        WebSocketConnection current = connection;
        if (!service.submit(() -> service.handle(current, line))) {
            String reply = (service.draining() ? "ERROR shutting_down" : "ERROR busy") + reference(line);
            if (ctx.channel().isWritable()) ctx.writeAndFlush(new TextWebSocketFrame(reply));
        }
    }

    // SAY 명령이면 메시지 ID를 오류 응답에 붙여 클라이언트가 어느 메시지인지 알 수 있게 합니다.
    private static String reference(String line) {
        String[] parts = line.split(" ", 3);
        boolean say = parts.length == 3 && parts[0].equals("SAY") && ChatService.isMessageId(parts[1]);
        return say ? " " + parts[1] : "";
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (connection != null) {
            WebSocketConnection closed = connection;
            connection = null;
            service.cleanup(() -> service.disconnected(closed));
            LOG.log(System.Logger.Level.INFO, "event=ws_closed channel=" + closed.id() + " user=" + closed.user());
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(System.Logger.Level.WARNING, "event=channel_failed channel="
                + ctx.channel().id().asShortText() + " cause=" + cause.getClass().getSimpleName());
        ctx.close();
    }
}
