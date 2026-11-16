# 10장. 메시지 경계를 구분하고 텍스트 채팅 만들기


## 포함 파일

- `LineChatClient.java`
- `LineChatServer.java`

## 실행 방법

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.LineChatServer
```

클라이언트는 터미널을 두 개 더 열어 실행합니다.

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.LineChatClient
```

한 줄씩 입력합니다. `/quit` 또는 입력 종료로 끝냅니다.

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
