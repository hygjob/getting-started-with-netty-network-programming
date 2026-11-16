package example.netty.echo;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.File;
import javax.net.ssl.SSLException;

// 인증서와 신뢰 자료를 보유하는 SslContext를 만듭니다. 연결마다 재사용할 수 있습니다.
public final class TlsContexts {
    private TlsContexts() { }

    public static SslContext server(boolean mutual) throws SSLException {
        SslContextBuilder builder = SslContextBuilder.forServer(
                new File("certs/server.crt"), new File("certs/server.key"))
                .sslProvider(SslProvider.JDK)
                .protocols("TLSv1.3", "TLSv1.2");
        if (mutual) {
            builder.trustManager(new File("certs/ca.crt"))
                    .clientAuth(ClientAuth.REQUIRE);
        }
        return builder.build();
    }

    public static SslContext client(boolean mutual) throws SSLException {
        SslContextBuilder builder = SslContextBuilder.forClient()
                .sslProvider(SslProvider.JDK)
                .protocols("TLSv1.3", "TLSv1.2")
                .trustManager(new File("certs/ca.crt"))
                // 기본값에만 의존하지 않고 호스트 이름 검증 알고리즘을 명시합니다.
                .endpointIdentificationAlgorithm("HTTPS");
        if (mutual) {
            builder.keyManager(new File("certs/client.crt"), new File("certs/client.key"));
        }
        return builder.build();
    }
}
