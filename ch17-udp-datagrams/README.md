# 17장. UDP로 데이터그램 주고받기

## 포함 파일

- `StatusReport.java`
- `UdpStatusCollector.java`
- `UdpStatusSender.java`

## 실행 방법

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.UdpStatusCollector
```

다른 터미널:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.UdpStatusSender -Dexec.args=1
```

보고 번호를 섞어 보내려면 다음을 사용합니다.

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.UdpStatusSender -Dexec.args='1 disorder'
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
