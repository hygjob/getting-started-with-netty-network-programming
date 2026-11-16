# 5장. Bootstrap과 Channel로 연결 다루기

## 포함 파일

- `EchoClient.java`
- `EchoClientHandler.java`
- `EchoServerHandler.java`
- `LifecycleEchoServer.java`

## 실행 방법

4장의 에코 클라이언트와 서버 핸들러를 이 폴더에 함께 두었습니다.

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.LifecycleEchoServer
```

다른 터미널:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.EchoClient
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
