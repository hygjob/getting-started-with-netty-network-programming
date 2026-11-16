# 19장. 느린 클라이언트와 과부하에 대응하기

## 포함 파일

- `BinaryEchoClient.java`
- `BoundedEchoServer.java`
- `WireDecoder.java`
- `WireEncoder.java`
- `WireMessage.java`
- `WireProtocol.java`

## 실행 방법

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.BoundedEchoServer
```

간단한 정상 응답은 11장의 클라이언트로 확인합니다.

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.BinaryEchoClient
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
