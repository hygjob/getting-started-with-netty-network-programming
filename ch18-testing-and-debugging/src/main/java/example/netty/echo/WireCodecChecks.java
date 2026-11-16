package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.TooLongFrameException;

// 문서에 적은 고정 바이트를 기준으로 인코더와 디코더를 독립적으로 확인합니다.
public final class WireCodecChecks {
    private static final WireMessage MESSAGE = new WireMessage(WireMessage.REQUEST, 7, "가");
    private static final String HEX = "4e540101000000000000000700000003eab080";

    public static void main(String[] args) {
        checkKnownBytes();
        checkEverySplit();
        checkTwoFrames();
        checkInvalidFrames();
        System.out.println("코덱의 규약 바이트·분할 입력·복수 프레임·잘못된 입력 검증 완료");
    }

    private static EmbeddedChannel decoder() {
        return new EmbeddedChannel(new LengthFieldBasedFrameDecoder(
                WireProtocol.MAX_FRAME, 12, 4, 0, 0, true), new WireDecoder());
    }

    private static ByteBuf encode(WireMessage message) {
        EmbeddedChannel channel = new EmbeddedChannel(new WireEncoder());
        try {
            require(channel.writeOutbound(message), "송신 결과가 없습니다.");
            return channel.readOutbound(); // 큐에서 꺼낸 버퍼는 호출자가 소유합니다.
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void checkKnownBytes() {
        ByteBuf encoded = encode(MESSAGE);
        try {
            require(HEX.equals(ByteBufUtil.hexDump(encoded)), "규약 바이트 불일치");
        } finally {
            // finishAndReleaseAll은 이미 꺼낸 버퍼까지 찾아 해제하지 않습니다.
            encoded.release();
        }
        EmbeddedChannel channel = decoder();
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(HEX)));
            require(MESSAGE.equals(channel.readInbound()), "고정 바이트 디코딩 실패");
            require(channel.readInbound() == null, "예상하지 않은 추가 출력");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void checkEverySplit() {
        byte[] bytes = ByteBufUtil.decodeHexDump(HEX);
        // 헤더 중간과 한글 UTF-8 중간을 포함한 모든 분할 위치를 재현합니다.
        for (int split = 1; split < bytes.length; split++) {
            EmbeddedChannel channel = decoder();
            try {
                require(!channel.writeInbound(Unpooled.copiedBuffer(bytes, 0, split)),
                        "불완전한 프레임을 출력했습니다: " + split);
                require(channel.readInbound() == null, "중간 출력이 있습니다.");
                channel.writeInbound(Unpooled.copiedBuffer(bytes, split, bytes.length - split));
                require(MESSAGE.equals(channel.readInbound()), "분할 복원 실패: " + split);
                require(channel.readInbound() == null, "추가 출력이 있습니다.");
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    private static void checkTwoFrames() {
        EmbeddedChannel channel = decoder();
        ByteBuf joined = Unpooled.buffer();
        joined.writeBytes(ByteBufUtil.decodeHexDump(HEX));
        joined.writeBytes(ByteBufUtil.decodeHexDump(HEX));
        try {
            channel.writeInbound(joined); // 여기서 입력 소유권을 넘깁니다.
            require(MESSAGE.equals(channel.readInbound()), "첫 프레임 누락");
            require(MESSAGE.equals(channel.readInbound()), "두 번째 프레임 누락");
            require(channel.readInbound() == null, "세 번째 프레임이 생겼습니다.");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void checkInvalidFrames() {
        ByteBuf magic = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(HEX));
        magic.setShort(0, 0);
        reject(magic, CorruptedFrameException.class);
        ByteBuf tooLarge = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(HEX));
        tooLarge.setInt(12, WireProtocol.MAX_BODY + 1);
        reject(tooLarge, TooLongFrameException.class);
        ByteBuf utf8 = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(HEX));
        utf8.setByte(16, 0xff);
        reject(utf8, java.nio.charset.CharacterCodingException.class);
    }

    private static void reject(ByteBuf input, Class<? extends Throwable> expected) {
        EmbeddedChannel channel = decoder();
        boolean rejected = false;
        try {
            channel.writeInbound(input);
        } catch (Exception cause) {
            // 어떤 예외든 나면 통과시키지 않고 기대한 오류 종류를 cause 체인에서 찾습니다.
            for (Throwable current = cause; current != null; current = current.getCause()) {
                if (expected.isInstance(current)) rejected = true;
            }
            if (!rejected) throw new AssertionError("다른 원인의 실패", cause);
        } finally {
            channel.finishAndReleaseAll();
        }
        require(rejected, "잘못된 프레임을 거부하지 않았습니다.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
