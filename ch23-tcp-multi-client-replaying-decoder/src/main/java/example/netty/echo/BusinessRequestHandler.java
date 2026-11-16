package example.netty.echo;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.CorruptedFrameException;
import java.nio.charset.CharacterCodingException;

// 연결 하나의 요청을 업무 서비스로 전달하고 결과 또는 업무 오류를 돌려줍니다.
public final class BusinessRequestHandler
        extends SimpleChannelInboundHandler<WireMessage> {
    private final ConnectionRegistry connections;
    private final BusinessService businessService;

    public BusinessRequestHandler(ConnectionRegistry connections,
                                  BusinessService businessService) {
        this.connections = connections;
        this.businessService = businessService;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        connections.connected(ctx.channel());
        ctx.fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WireMessage message)
            throws CharacterCodingException {
        if (message.type() != WireMessage.REQUEST) {
            throw new CorruptedFrameException("서버는 REQUEST만 받을 수 있습니다.");
        }
        try {
            BusinessService.BusinessResult result = businessService.execute(message.body());
            int requestBytes = WireProtocol.encodeBody(message.body()).length;
            System.out.printf("[요청] id=%s requestId=%d operation=%s bodyBytes=%d%n",
                    ctx.channel().id().asShortText(), message.requestId(),
                    result.operation(), requestBytes);
            write(ctx, new WireMessage(
                    WireMessage.RESPONSE, message.requestId(), result.body()));
        } catch (IllegalArgumentException | ArithmeticException cause) {
            String error = "업무 오류: " + cause.getMessage();
            System.out.printf("[업무 오류] id=%s requestId=%d reason=%s%n",
                    ctx.channel().id().asShortText(), message.requestId(), cause.getMessage());
            write(ctx, new WireMessage(WireMessage.ERROR, message.requestId(), error));
        }
    }

    private static void write(ChannelHandlerContext ctx, WireMessage response) {
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        connections.disconnected(ctx.channel());
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.printf("[연결 오류] id=%s cause=%s%n",
                ctx.channel().id().asShortText(), cause.getMessage());
        ctx.close();
    }
}
