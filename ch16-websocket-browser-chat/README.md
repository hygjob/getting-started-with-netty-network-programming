# 16장. WebSocket으로 브라우저와 실시간 통신하기

## 포함 파일

- `TlsContexts.java`
- `WebSocketChatServer.java`

## 실행 방법

12자 이상의 실습 접속 암호를 환경 변수로 전달합니다.

```bash
read -r -s -p '실습 접속 암호: ' NETTY_CHAT_PASSWORD
export NETTY_CHAT_PASSWORD
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.WebSocketChatServer
```

브라우저에서 `http://localhost:8080/`을 엽니다. 파일로 `chat.html`을 직접 열지 마시기 바랍니다.

TLS 모드(WSS)는 15장과 같은 인증서가 필요합니다. 이 폴더에서 `../ch15-tls-and-secure-connections/generate-certs.sh`를 실행한 뒤 `certs/`를 이 폴더로 복사하거나, 15장 폴더의 인증서 경로에 맞춰 실행하시기 바랍니다.

```bash
cp -R ../ch15-tls-and-secure-connections/certs ./certs
mvn exec:java -Dexec.mainClass=example.netty.echo.WebSocketChatServer -Dexec.args=tls
```

브라우저에서는 `https://localhost:8443/`을 엽니다.

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 Enter로 종료합니다.
