# 4장. Netty 4.2로 첫 에코 서버 만들기


## 포함 파일

- `EchoClient.java`
- `EchoClientHandler.java`
- `EchoServer.java`
- `EchoServerHandler.java`

## 실행 방법

```bash
mvn compile
```

첫 터미널:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.EchoServer
```

둘째 터미널:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.EchoClient
```

서버는 Enter로 종료합니다.

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
