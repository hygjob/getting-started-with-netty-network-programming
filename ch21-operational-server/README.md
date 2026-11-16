# 21장. 운영 가능한 서버로 다듬기

## 포함 파일

- `ManagedEchoServer.java`
- `ServerSettings.java`
- `WireDecoder.java`
- `WireEncoder.java`
- `WireMessage.java`
- `WireProtocol.java`

## 실행 방법

설정은 환경 변수로 바꿀 수 있습니다. 값이 없으면 본문의 기본값을 사용합니다.

```bash
mvn compile
NETTY_PORT=8000 NETTY_MAX_IN_FLIGHT=128 NETTY_DRAIN_MILLIS=5000   mvn exec:java -Dexec.mainClass=example.netty.echo.ManagedEchoServer
```

다른 터미널에서 11장의 `BinaryEchoClient`나 20장의 `EchoLoadClient`를 실행해 관찰할 수 있습니다. 이 폴더에는 서버만 들어 있습니다.

서버 터미널에서 Enter를 누르거나 종료 신호를 보내면 드레인 후 종료합니다.

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
