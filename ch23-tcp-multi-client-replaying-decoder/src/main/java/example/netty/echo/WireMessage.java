package example.netty.echo;

import java.util.Objects;

// 헤더를 해석한 뒤 업무 핸들러가 다루는 불변 메시지입니다.
public record WireMessage(int type, long requestId, String body) {
    public static final int REQUEST = 1;
    public static final int RESPONSE = 2;
    public static final int ERROR = 3;

    public WireMessage {
        Objects.requireNonNull(body, "body");
    }
}
