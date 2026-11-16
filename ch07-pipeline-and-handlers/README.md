# 7장. ChannelPipeline과 ChannelHandler 이해하기

## 포함 파일

- `EchoClient.java`
- `EchoClientHandler.java`
- `PipelineTraceServer.java`

## 실행 방법

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.PipelineTraceServer
```

다른 터미널:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.EchoClient
```

`channel.write()` 경로를 비교하려면 서버를 다음처럼 실행합니다.

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.PipelineTraceServer -Dexec.args=channel
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
