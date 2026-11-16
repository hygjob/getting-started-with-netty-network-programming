# 23장. TCP 단일 서버와 다중 클라이언트 실습

## 포함 파일

- `MultiClientBusinessServer.java`
- `MultiClientDemo.java`
- `BusinessService.java`
- `BusinessRequestHandler.java`
- `ReplayWireDecoder.java`
- `WireEncoder.java`
- `WireMessage.java`
- `WireProtocol.java`
- `ConnectionRegistry.java`

## 실행 방법

서버를 먼저 실행합니다.

```bash
mvn compile
mvn exec:java "-Dexec.mainClass=example.netty.echo.MultiClientBusinessServer"
```

다른 터미널에서 세 개의 TCP 연결을 열어 `UPPER`, `REVERSE`, `SUM` 업무를 각각 요청하는 데모 클라이언트를 실행합니다. 각 요청의 UTF-8 본문 길이도 서로 다릅니다.

```bash
mvn exec:java "-Dexec.mainClass=example.netty.echo.MultiClientDemo"
```

클라이언트 수를 바꾸려면 양의 정수를 인자로 전달합니다.

```bash
mvn exec:java "-Dexec.mainClass=example.netty.echo.MultiClientDemo" "-Dexec.args=5"
```

기본 TCP 포트는 `9000`입니다. 서버를 종료하려면 서버 터미널에서 Enter를 누르십시오.

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- `ReplayWireDecoder`는 채널마다 새 인스턴스를 만들어야 합니다. `@Sharable`을 붙이거나 한 인스턴스를 여러 파이프라인에 넣으면 안 됩니다.
- 이 디코더는 가변 길이 프로토콜을 이해하기 위한 실습용입니다. 운영 코드에서는 11장의 `LengthFieldBasedFrameDecoder`와 `WireDecoder` 조합도 함께 비교하십시오.
