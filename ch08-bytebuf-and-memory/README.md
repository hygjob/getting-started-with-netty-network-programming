# 8장. ByteBuf와 메모리 수명 이해하기

## 포함 파일

- `AllocatorDemo.java`
- `ByteBufIndicesDemo.java`
- `ByteBufViewsDemo.java`
- `EchoClient.java`
- `EchoClientHandler.java`
- `EchoServer.java`
- `RetainedEchoHandler.java`

## 실행 방법

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.ByteBufIndicesDemo
mvn exec:java -Dexec.mainClass=example.netty.echo.ByteBufViewsDemo
mvn exec:java -Dexec.mainClass=example.netty.echo.AllocatorDemo
```

`EchoServer`는 본문의 안내대로 `RetainedEchoHandler`를 사용하도록 구성했습니다.

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.EchoServer
```

다른 터미널:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.EchoClient
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
