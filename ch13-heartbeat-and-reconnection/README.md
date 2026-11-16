# 13장. 오래 유지되는 연결을 안정적으로 관리하기

## 포함 파일

- `BinaryEchoServer.java`
- `HeartbeatHandler.java`
- `ReconnectingClient.java`
- `WireDecoder.java`
- `WireEncoder.java`
- `WireMessage.java`
- `WireProtocol.java`

## 실행 방법

11장의 바이너리 서버를 이 폴더에 함께 두었습니다.

서버를 아직 켜지 않은 채로 클라이언트를 먼저 실행하면 재연결 대기를 관찰할 수 있습니다.

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.ReconnectingClient
```

다른 터미널에서 서버를 시작합니다.

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.BinaryEchoServer
```

연결은 유지하되 PONG을 보내지 않으려면 다음처럼 실행합니다.

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.BinaryEchoServer -Dexec.args=no-pong
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
