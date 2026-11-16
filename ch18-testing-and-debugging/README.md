# 18장. Netty 애플리케이션 검증과 디버깅

## 포함 파일

- `WireCodecChecks.java`
- `WireDecoder.java`
- `WireEncoder.java`
- `WireMessage.java`
- `WireProtocol.java`

## 실행 방법

실제 소켓 없이 코덱을 검증합니다.

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.WireCodecChecks
```

누수 진단은 11장 서버를 사용합니다.

```bash
cd ../ch11-custom-binary-protocol
MAVEN_OPTS='-Dio.netty.leakDetection.level=paranoid' \
  mvn exec:java -Dexec.mainClass=example.netty.echo.BinaryEchoServer
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
