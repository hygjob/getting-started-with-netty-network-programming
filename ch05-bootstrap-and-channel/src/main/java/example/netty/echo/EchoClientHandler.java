package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

// 수신 내용을 배열에 복사합니다. 처리한 ByteBuf는 기본 설정에 따라 자동 해제됩니다.
public final class EchoClientHandler extends SimpleChannelInboundHandler<ByteBuf> {
    // 에코 응답과 비교할 요청 원본입니다. 전송 중에 내용을 바꾸지 않습니다.
    private final byte[] expected;
    // 여러 수신 이벤트의 조각을 모을 저장 공간과 현재 누적 바이트 수입니다.
    private final byte[] received;
    private int receivedBytes;
    // 이벤트 루프에서 기록한 결과를 main 스레드에서도 확인할 수 있게 합니다.
    private volatile boolean success;

    public EchoClientHandler(byte[] expected) {
        // 이 예제는 응답 수신으로 완료를 판단하므로 빈 요청은 받지 않습니다.
        if (expected.length == 0) {
            throw new IllegalArgumentException("빈 요청은 이 예제에서 사용하지 않습니다.");
        }
        // 호출자가 전달한 원본 배열을 바꾸어도 전송 내용이 달라지지 않게 복제합니다.
        this.expected = expected.clone();
        // 고정된 요청의 에코이므로 응답 바이트 수를 미리 알고 있습니다.
        this.received = new byte[expected.length];
    }

    // 연결이 활성화된 뒤 요청을 보냅니다.
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        System.out.println("송신: " + new String(expected, StandardCharsets.UTF_8));
        // 배열을 복사하지 않고 ByteBuf로 감싸 쓰기 경로에 넘깁니다.
        // 쓰기 완료까지 기다리지 않으며, 실패하거나 취소되면 연결을 닫습니다.
        ctx.writeAndFlush(Unpooled.wrappedBuffer(expected))
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        // 파이프라인의 다음 인바운드 핸들러로 활성화 이벤트를 전달합니다.
        ctx.fireChannelActive();
    }

    // 현재 조각을 처리합니다. 메서드가 끝나면 Netty가 이 수신 버퍼를 해제합니다.
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        int count = msg.readableBytes();
        // 아직 받아야 할 바이트 수를 계산해 누적 배열의 범위를 확인합니다.
        int remaining = received.length - receivedBytes;
        // 기대한 길이를 넘는 입력은 복사하기 전에 실패로 처리합니다.
        if (count > remaining) {
            throw new IllegalStateException("예상보다 많은 응답 바이트를 받았습니다.");
        }

        // 이전 수신 내용 뒤에 현재 조각을 복사합니다. msg를 보관하거나 직접 해제하지 않습니다.
        msg.readBytes(received, receivedBytes, count);
        receivedBytes += count;
        System.out.println("응답 누적: " + receivedBytes + "/" + received.length);

        // 전체 길이가 모였을 때만 내용 비교와 문자열 변환을 수행합니다.
        // 아직 부족하면 메서드를 마치고 다음 수신 이벤트를 기다립니다.
        if (receivedBytes == received.length) {
            // 길이가 같아도 내용이 다를 수 있으므로 원본 바이트와 비교합니다.
            if (!Arrays.equals(expected, received)) {
                throw new IllegalStateException("송신한 바이트와 응답이 다릅니다.");
            }
            // 응답 일치가 확인된 뒤 성공을 기록하고 연결을 닫습니다.
            success = true;
            System.out.println("수신: " + new String(received, StandardCharsets.UTF_8));
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 채널이 닫혔다는 사실만으로 요청 성공을 판단하지 않습니다.
        if (!success) {
            System.err.println("응답을 완성하지 못한 채 연결이 종료되었습니다: "
                    + receivedBytes + "/" + received.length);
        }
        // 다음 인바운드 핸들러에도 연결 비활성화 이벤트를 전달합니다.
        ctx.fireChannelInactive();
    }

    // 길이 초과나 내용 불일치 등을 기록하고 연결을 정리합니다.
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("클라이언트 오류: " + cause);
        ctx.close();
    }

    // main 스레드가 연결 종료 후 실제 에코 성공 여부를 확인할 때 사용합니다.
    public boolean isSuccess() {
        return success;
    }
}
