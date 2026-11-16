# 11장. 사용자 정의 바이너리 프로토콜 만들기


## 포함 파일

- `BinaryEchoClient.java`
- `BinaryEchoServer.java`
- `DirectWireDecoder.java`
- `WireDecoder.java`
- `WireEncoder.java`
- `WireMessage.java`
- `WireProtocol.java`

## 실행 방법

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.BinaryEchoServer
```

다른 터미널:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.BinaryEchoClient
```

`DirectWireDecoder`는 학습용 대안이며 기본 서버·클라이언트는 사용하지 않습니다.

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
