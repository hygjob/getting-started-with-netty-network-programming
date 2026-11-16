# 9장. Future와 Promise로 비동기 결과 처리하기

## 포함 파일

- `AsyncEchoClient.java`
- `EchoServer.java`
- `EchoServerHandler.java`
- `PromiseDemo.java`

## 실행 방법

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.PromiseDemo
mvn exec:java -Dexec.mainClass=example.netty.echo.PromiseDemo -Dexec.args=2147483647
```

비동기 에코 교환은 먼저 서버를 실행합니다.

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.EchoServer
```

다른 터미널:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.AsyncEchoClient
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
