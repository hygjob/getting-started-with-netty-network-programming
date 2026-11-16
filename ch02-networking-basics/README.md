# 2장. 네트워크 프로그래밍에 필요한 기초



## 포함 파일

- `BlockingEchoClient.java`
- `BlockingEchoServer.java`
- `BytesExample.java`

## 실행 방법

JDK 21이 필요합니다. Maven은 사용하지 않습니다. 이 폴더에서 컴파일합니다.

```bash
javac -encoding UTF-8 BytesExample.java
java BytesExample
```

에코 서버와 클라이언트는 터미널을 두 개 사용합니다.

```bash
javac -encoding UTF-8 BlockingEchoServer.java BlockingEchoClient.java
```

첫 터미널:

```bash
java BlockingEchoServer
```

둘째 터미널:

```bash
java BlockingEchoClient
java BlockingEchoClient "두 번째 메시지입니다."
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
