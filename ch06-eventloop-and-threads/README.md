# 6장. EventLoop와 스레드 모델 이해하기


## 포함 파일

- `EchoClient.java`
- `EchoClientHandler.java`
- `EventLoopTasksDemo.java`
- `OffloadedEchoServer.java`

## 실행 방법

```bash
mvn compile
```

이벤트 루프에서 바로 처리하는 모드:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.OffloadedEchoServer -Dexec.args=inline
```

다른 터미널에서 클라이언트를 실행합니다.

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.EchoClient
```

별도 실행기로 넘기는 기본 모드:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.OffloadedEchoServer
```

예약 작업 관찰:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.EventLoopTasksDemo
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
