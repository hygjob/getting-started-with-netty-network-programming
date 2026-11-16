package example.netty.echo;

import io.netty.handler.codec.CorruptedFrameException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

// 11장의 헤더 형식을 이 실습에서도 사용합니다.
public final class WireProtocol {
    public static final int MAGIC = 0x4E54;
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 16;
    public static final int MAX_BODY = 4096;

    private WireProtocol() { }

    public static void validate(int type, long requestId, int bodyLength) {
        if (type < WireMessage.REQUEST || type > WireMessage.ERROR) {
            throw new CorruptedFrameException("알 수 없는 메시지 타입: " + type);
        }
        if (requestId <= 0) {
            throw new CorruptedFrameException("요청 ID는 1 이상이어야 합니다.");
        }
        if (bodyLength < 0 || bodyLength > MAX_BODY) {
            throw new CorruptedFrameException("허용 범위를 벗어난 본문 길이: " + bodyLength);
        }
    }

    public static String decodeBody(byte[] bytes) throws CharacterCodingException {
        // 잘못된 UTF-8을 대체 문자로 바꾸지 않고 프로토콜 오류로 처리합니다.
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    public static byte[] encodeBody(String body) throws CharacterCodingException {
        if (body.length() > MAX_BODY) {
            throw new IllegalArgumentException("본문이 너무 깁니다.");
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(body));
        if (encoded.remaining() > MAX_BODY) {
            throw new IllegalArgumentException("UTF-8 본문이 너무 깁니다.");
        }
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
    }
}
