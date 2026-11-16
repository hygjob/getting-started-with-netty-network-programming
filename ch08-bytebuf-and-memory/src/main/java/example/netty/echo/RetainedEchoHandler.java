package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

// 처리한 수신 버퍼를 메서드 종료 후 자동 해제합니다.
public final class RetainedEchoHandler extends SimpleChannelInboundHandler<ByteBuf> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        // 쓰기 경로가 보유할 참조를 하나 추가한 뒤 버퍼를 전달합니다.
        // 수신 측 자동 해제와 쓰기 측 해제가 각각 자기 참조를 반환하게 됩니다.
        ctx.writeAndFlush(msg.retain())
                // 비동기 쓰기가 실패하거나 취소되면 연결을 닫습니다.
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("에코 오류: " + cause);
        // 오류가 발생한 연결의 종료를 요청합니다.
        ctx.close();
    }
}
