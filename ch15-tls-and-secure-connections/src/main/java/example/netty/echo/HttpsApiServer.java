package example.netty.echo;

// 14장의 HTTP 서버에 서버 TLS 설정을 전달하는 진입점입니다.
public final class HttpsApiServer {
    public static void main(String[] args) throws Exception {
        HttpApiServer.run(TlsContexts.server(false), 8443, null);
    }
}
