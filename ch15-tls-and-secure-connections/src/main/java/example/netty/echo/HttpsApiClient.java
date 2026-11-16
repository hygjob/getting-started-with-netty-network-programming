package example.netty.echo;

// 피어 이름을 인자로 받아 이름 검증 실패도 확인할 수 있게 합니다.
public final class HttpsApiClient {
    public static void main(String[] args) throws Exception {
        String peerHost = args.length == 0 ? "localhost" : args[0];
        HttpApiClient.run(TlsContexts.client(false), peerHost, 8443);
    }
}
