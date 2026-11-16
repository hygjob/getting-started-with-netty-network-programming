package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

// 수신 버퍼를 그대로 쓰기 경로에 넘기는 에코 핸들러입니다.
public final class EchoServerHandler extends ChannelInboundHandlerAdapter {
    // 연결이 활성화되면 Netty가 이 메서드를 호출합니다.
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        System.out.println("연결: " + ctx.channel().remoteAddress()
                + " / 스레드: " + Thread.currentThread().getName());
        // 현재 핸들러 뒤의 인바운드 핸들러에도 활성화 이벤트를 전달합니다.
        ctx.fireChannelActive();
    }

    // 한 번의 호출에는 전체 요청이 아니라 일부 바이트만 들어올 수도 있습니다.
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // 이 파이프라인에는 객체 디코더가 없으므로 수신 메시지는 ByteBuf입니다.
        ByteBuf buffer = (ByteBuf) msg;
        System.out.println("수신 바이트 수: " + buffer.readableBytes());
        // 문자열로 변환하지 않고 받은 바이트를 그대로 돌려줍니다.
        // 버퍼 수명 관리를 쓰기 경로로 넘기므로 여기서 다시 release()하지 않습니다.
        // 완료를 기다리지 않고, 쓰기가 실패하거나 취소되면 연결을 닫도록 등록합니다.
        ctx.writeAndFlush(buffer).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    // 연결이 비활성화되었을 때 기록합니다.
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.out.println("연결 종료: " + ctx.channel().remoteAddress());
        // 다음 인바운드 핸들러도 연결 종료를 알 수 있도록 이벤트를 전달합니다.
        ctx.fireChannelInactive();
    }

    // 예외를 기록하고 문제가 생긴 연결의 종료를 요청합니다.
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("서버 연결 오류: " + cause);
        ctx.close();
    }
}
