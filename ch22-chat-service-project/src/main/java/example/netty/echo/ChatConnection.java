package example.netty.echo;

// 서비스 로직이 보는 연결입니다. 서비스는 Netty 채널 대신 이 계약에만 의존합니다.
public interface ChatConnection {
    String id();

    String user();

    // 서비스 실행기에서만 호출합니다. 보내지 못하고 버렸다면 false를 반환합니다.
    boolean send(String line);

    void close(int code, String reason);
}
