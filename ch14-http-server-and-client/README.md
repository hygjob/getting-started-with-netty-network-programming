# 14장. HTTP 서버와 클라이언트 만들기

## 포함 파일

- `HttpApiClient.java`
- `HttpApiHandler.java`
- `HttpApiServer.java`

## 실행 방법

기본 포트는 `127.0.0.1:8080`입니다. 앞 장의 8000번 서버와 동시에 실행해도 포트는 겹치지 않습니다.

```bash
mvn compile
mvn exec:java -Dexec.mainClass=example.netty.echo.HttpApiServer
```

다른 터미널:

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.HttpApiClient
curl --http1.1 -i http://localhost:8080/hello
```

로컬 파일을 다운로드 대상으로 쓰려면 `sample.txt`를 인자로 전달합니다.

```bash
mvn exec:java -Dexec.mainClass=example.netty.echo.HttpApiServer -Dexec.args=sample.txt
```

## 공통 주의

- JDK 21과 UTF-8을 사용합니다.
- 같은 포트를 쓰는 다른 예제를 먼저 종료하시기 바랍니다. 기본 TCP 포트는 8000, HTTP는 8080, TLS는 8443입니다.
- 서버 예제는 Enter로 종료합니다.
