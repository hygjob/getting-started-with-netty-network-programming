# 20장. 성능을 측정하고 트랜스포트 선택하기

## 포함 파일

- `EchoLoadClient.java`
- `TransportChoice.java`
- `TransportEchoServer.java`
- `WireDecoder.java`
- `WireEncoder.java`
- `WireMessage.java`
- `WireProtocol.java`

## 실행 방법

기본값은 NIO입니다. epoll·kqueue·io_uring 클래스 모듈은 컴파일용으로 포함했으며, 네이티브 바이너리는 환경에 맞게 추가하시기 바랍니다.

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.TransportEchoServer -Dexec.args='nio 2'
```

다른 터미널:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.EchoLoadClient -Dexec.args=16
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
