# 3장. 블로킹 I/O에서 이벤트 기반 I/O로


## 포함 파일

- `BlockingEchoClient.java`
- `NioEchoServer.java`

## 실행 방법

2장의 `BlockingEchoClient`를 이 폴더에 함께 두었습니다. JDK 21로 컴파일합니다.

```bash
javac -encoding UTF-8 NioEchoServer.java BlockingEchoClient.java
```

첫 터미널:

```bash
java NioEchoServer
```

둘째 터미널:

```bash
java BlockingEchoClient "NIO 서버에서도 한글을 주고받습니다."
```

서버는 Enter로 종료합니다.

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
