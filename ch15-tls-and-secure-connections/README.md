# 15장. TLS로 통신 보호하기

## 포함 파일

- `HttpApiClient.java`
- `HttpApiHandler.java`
- `HttpApiServer.java`
- `HttpsApiClient.java`
- `HttpsApiServer.java`
- `TlsContexts.java`
- `TlsEchoClient.java`
- `TlsEchoServer.java`
- `WireDecoder.java`
- `WireEncoder.java`
- `WireMessage.java`
- `WireProtocol.java`

## 실행 방법

프로젝트 루트에서 인증서를 먼저 만듭니다. OpenSSL 3.x가 필요합니다.

```bash
./generate-certs.sh
```

TLS 에코:

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.TlsEchoServer
```

다른 터미널:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.TlsEchoClient
```

HTTPS는 에코 서버를 종료한 뒤 실행합니다. 포트 8443을 함께 씁니다.

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.HttpsApiServer
```

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.HttpsApiClient
curl --http1.1 --cacert certs/ca.crt https://localhost:8443/hello
```

이름 검증 실패를 관찰하려면 피어 이름을 IP로 지정합니다.

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.HttpsApiClient -Dexec.args=127.0.0.1
```

상호 TLS:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.TlsEchoServer -Dexec.args=mtls
mvn exec:java -Dexec.mainClass=example.netty.echo.TlsEchoClient -Dexec.args=mtls
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
