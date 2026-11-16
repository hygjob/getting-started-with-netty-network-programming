# 처음 시작하는 Netty 네트워크 프로그래밍 - 예제

이 디렉터리는 본문 각 장의 자바 예제를 챕터별 폴더로 모아 둔 것입니다.
4장부터는 본문과 같이 Maven 프로젝트이며, Netty 버전은 `4.2.19.Final`입니다.

## 준비

```bash
java -version    # 21
javac -version   # 21
mvn -version     # Java 21을 사용하는지 확인
```

2장과 3장은 Maven 없이 `javac`와 `java`로 실행합니다.
4장 이후는 해당 폴더에서 `mvn compile` 뒤 `mvn exec:java`를 사용합니다.
본문의 학습용 프로젝트 이름은 `netty-echo`이며, 이 폴더의 Maven `artifactId`는 장 폴더 이름입니다.

## 장별 폴더

- [`ch02-networking-basics/`](ch02-networking-basics/) — 2장. 네트워크 프로그래밍에 필요한 기초
- [`ch03-blocking-io-to-event-loop/`](ch03-blocking-io-to-event-loop/) — 3장. 블로킹 I/O에서 이벤트 기반 I/O로
- [`ch04-first-netty-echo/`](ch04-first-netty-echo/) — 4장. Netty 4.2로 첫 에코 서버 만들기
- [`ch05-bootstrap-and-channel/`](ch05-bootstrap-and-channel/) — 5장. Bootstrap과 Channel로 연결 다루기
- [`ch06-eventloop-and-threads/`](ch06-eventloop-and-threads/) — 6장. EventLoop와 스레드 모델 이해하기
- [`ch07-pipeline-and-handlers/`](ch07-pipeline-and-handlers/) — 7장. ChannelPipeline과 ChannelHandler 이해하기
- [`ch08-bytebuf-and-memory/`](ch08-bytebuf-and-memory/) — 8장. ByteBuf와 메모리 수명 이해하기
- [`ch09-future-and-promise/`](ch09-future-and-promise/) — 9장. Future와 Promise로 비동기 결과 처리하기
- [`ch10-framing-and-text-chat/`](ch10-framing-and-text-chat/) — 10장. 메시지 경계를 구분하고 텍스트 채팅 만들기
- [`ch11-custom-binary-protocol/`](ch11-custom-binary-protocol/) — 11장. 사용자 정의 바이너리 프로토콜 만들기
- [`ch12-request-response-client/`](ch12-request-response-client/) — 12장. 요청과 응답을 주고받는 클라이언트 만들기
- [`ch13-heartbeat-and-reconnection/`](ch13-heartbeat-and-reconnection/) — 13장. 오래 유지되는 연결을 안정적으로 관리하기
- [`ch14-http-server-and-client/`](ch14-http-server-and-client/) — 14장. HTTP 서버와 클라이언트 만들기
- [`ch15-tls-and-secure-connections/`](ch15-tls-and-secure-connections/) — 15장. TLS로 통신 보호하기
- [`ch16-websocket-browser-chat/`](ch16-websocket-browser-chat/) — 16장. WebSocket으로 브라우저와 실시간 통신하기
- [`ch17-udp-datagrams/`](ch17-udp-datagrams/) — 17장. UDP로 데이터그램 주고받기
- [`ch18-testing-and-debugging/`](ch18-testing-and-debugging/) — 18장. Netty 애플리케이션 검증과 디버깅
- [`ch19-backpressure-and-overload/`](ch19-backpressure-and-overload/) — 19장. 느린 클라이언트와 과부하에 대응하기
- [`ch20-performance-and-transports/`](ch20-performance-and-transports/) — 20장. 성능을 측정하고 트랜스포트 선택하기
- [`ch21-operational-server/`](ch21-operational-server/) — 21장. 운영 가능한 서버로 다듬기
- [`ch22-chat-service-project/`](ch22-chat-service-project/) — 22장. 종합 프로젝트: 운영 기능을 갖춘 실시간 채팅 서비스
- [`ch23-tcp-multi-client-replaying-decoder/`](ch23-tcp-multi-client-replaying-decoder/) — 23장. TCP 단일 서버와 다중 클라이언트 실습

## 실행 순서

1. 사용할 장 폴더로 들어갑니다.
2. 그 폴더의 `README.md`에 적힌 명령을 따릅니다.
3. 서버와 클라이언트를 함께 쓰는 장은 터미널을 나누어 실행합니다.

## 포트와 인증서

- TCP 예제는 `127.0.0.1:8000`을 사용합니다. 한 번에 서버 하나만 실행하시기 바랍니다.
- HTTP는 8080, TLS·HTTPS·WSS는 8443을 사용합니다.
- 15장과 16장의 TLS 실습은 `ch15-tls-and-secure-connections/generate-certs.sh`로 로컬 인증서를 만듭니다.
- 인증서 개인키와 `certs/`는 저장소에 커밋하지 마시기 바랍니다.
