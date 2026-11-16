# 12장. 요청과 응답을 주고받는 클라이언트 만들기

## 포함 파일

- `RequestSession.java`
- `RpcDemoClient.java`
- `RpcDemoServer.java`
- `WireDecoder.java`
- `WireEncoder.java`
- `WireMessage.java`
- `WireProtocol.java`

## 실행 방법

11장의 공통 코덱을 이 폴더에 함께 두었습니다.

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.RpcDemoServer
```

다른 터미널:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.RpcDemoClient
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
