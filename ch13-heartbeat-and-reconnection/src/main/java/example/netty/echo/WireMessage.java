package example.netty.echo;

import java.util.Objects;

// 헤더를 해석한 뒤 업무 핸들러가 다루는 불변 메시지입니다. ByteBuf는 보유하지 않습니다.
public record WireMessage(int type, long requestId, String body) {
    public static final int REQUEST = 1;
    public static final int RESPONSE = 2;
    public static final int ERROR = 3;
    public static final int PING = 4;
    public static final int PONG = 5;

    public WireMessage {
        // 전송 규약 검증은 공통 코덱에서 수행합니다. 여기서는 null 본문만 거부합니다.
        Objects.requireNonNull(body, "body");
    }
}
