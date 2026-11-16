package example.netty.echo;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

// 헤더 오프셋, 길이 계산, UTF-8 검증을 한곳에 모아 이후 장에서도 재사용합니다.
public final class WireProtocol {
    public static final int MAGIC = 0x4E54;
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 16;
    public static final int MAX_BODY = 4096;
    public static final int MAX_FRAME = HEADER_SIZE + MAX_BODY;

    private WireProtocol() { }

    public static void install(ChannelPipeline pipeline) {
        // 길이 필드는 오프셋 12의 4바이트이며 그 뒤가 모두 본문입니다.
        pipeline.addLast("frame", new LengthFieldBasedFrameDecoder(
                MAX_FRAME, 12, 4, 0, 0, true));
        pipeline.addLast("decode", new WireDecoder());
        pipeline.addLast("encode", new WireEncoder());
    }

    static void validate(int type, long id, int bodyLength) {
        if (type < WireMessage.REQUEST || type > WireMessage.PONG) {
            throw new CorruptedFrameException("알 수 없는 타입: " + type);
        }
        if (id <= 0 || bodyLength < 0 || bodyLength > MAX_BODY) {
            throw new CorruptedFrameException("요청 ID 또는 본문 길이 오류");
        }
        if ((type == WireMessage.PING || type == WireMessage.PONG)
                && bodyLength != 0) {
            throw new CorruptedFrameException("PING/PONG의 본문은 비어 있어야 합니다.");
        }
    }

    static WireMessage decode(ByteBuf frame) throws CharacterCodingException {
        int start = frame.readerIndex();
        if (frame.readableBytes() < HEADER_SIZE) {
            throw new CorruptedFrameException("헤더 부족");
        }
        // get* 메서드는 읽기 위치를 옮기지 않으므로 프레임 시작을 기준으로 검사합니다.
        if (frame.getUnsignedShort(start) != MAGIC
                || frame.getUnsignedByte(start + 2) != VERSION) {
            throw new CorruptedFrameException("매직 값 또는 버전 오류");
        }
        int type = frame.getUnsignedByte(start + 3);
        long id = frame.getLong(start + 4);
        int length = frame.getInt(start + 12);
        validate(type, id, length);
        if (frame.readableBytes() != HEADER_SIZE + length) {
            throw new CorruptedFrameException("본문 길이 불일치");
        }
        // 잘못된 UTF-8을 대체 문자로 바꾸지 않고 거부합니다.
        String body = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(frame.nioBuffer(start + HEADER_SIZE, length)).toString();
        return new WireMessage(type, id, body);
    }

    static ByteBuffer encodeBody(String body) throws CharacterCodingException {
        // UTF-8 바이트 배열을 만들기 전에도 지나치게 큰 문자열을 거부합니다.
        if (body.length() > MAX_BODY) {
            throw new IllegalArgumentException("본문이 너무 깁니다.");
        }
        return StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(body));
    }
}
