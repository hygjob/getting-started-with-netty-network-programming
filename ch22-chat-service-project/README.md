# 22장. 종합 프로젝트: 운영 기능을 갖춘 실시간 채팅 서비스

## 포함 파일

- `ChatChecks.java`
- `ChatConnection.java`
- `ChatFrameHandler.java`
- `ChatServer.java`
- `ChatService.java`
- `ChatSettings.java`
- `ChatUpgradeGate.java`
- `SessionStore.java`
- `TlsContexts.java`
- `WebSocketConnection.java`

## 실행 방법

먼저 네트워크 없이 시나리오 검증을 실행합니다.

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.ChatChecks
```

서버는 12자 이상의 실습 접속 암호를 환경 변수로 받습니다.

```bash
read -r -s -p '실습 접속 암호: ' NETTY_CHAT_PASSWORD
export NETTY_CHAT_PASSWORD
mvn exec:java -Dexec.mainClass=example.netty.echo.ChatServer
```

브라우저에서 `http://localhost:8080/`을 열고 사용자 이름과 암호로 로그인한 뒤 연결하고 채팅방에 입장합니다. 다른 브라우저 프로필에서 다른 사용자 이름으로 같은 방에 들어가 대화를 확인합니다.

준비 상태 확인:

```bash
curl -i http://localhost:8080/ready
```

TLS 모드(HTTPS·WSS)는 15장의 인증서를 이 폴더의 `certs/`에 두고 실행합니다.

```bash
cp -R ../ch15-tls-and-secure-connections/certs ./certs
NETTY_TLS=true mvn exec:java -Dexec.mainClass=example.netty.echo.ChatServer
```

한도와 시간 설정은 본문 22.8절의 환경 변수 표를 참고하시기 바랍니다.

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
