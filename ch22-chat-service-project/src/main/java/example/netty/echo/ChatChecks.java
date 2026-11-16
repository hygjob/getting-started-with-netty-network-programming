package example.netty.echo;

import io.netty.buffer.Unpooled;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

// 네트워크 없이 서비스 시나리오와 전송 계층의 경계 동작을 확인합니다.
public final class ChatChecks {
    public static void main(String[] args) throws Exception {
        System.out.println("==========joinMessageAndLeave");
        joinMessageAndLeave();
        System.out.println("==========duplicateAfterReconnect");
        duplicateAfterReconnect();
        System.out.println("==========replacedConnection");
        replacedConnection();
        System.out.println("==========invalidInputAndLimits");
        invalidInputAndLimits();
        System.out.println("==========overloadAndDraining");
        overloadAndDraining();
        System.out.println("==========slowClient");
        slowClient();
        System.out.println("==========transportBoundary");
        transportBoundary();
        //System.out.println("입장·퇴장·중복·대체·잘못된 입력·과부하·종료·느린 사용자·전송 경계 검증 완료");
    }

    // 방 2개, 방마다 2명, 본문 32바이트로 한도를 작게 잡아 경계에 빨리 도달하게 합니다.
    private static ChatSettings settings(int maxPending) {
        return new ChatSettings("127.0.0.1", 8080, false, "practice-password",
                10, 2, 2, 32, maxPending, 100, 60_000, 0);
    }

    // 직접 익스큐터: 제출한 작업을 호출한 스레드에서 바로 실행해 결과를 순서대로 확인합니다.
    private static ChatService direct() { return new ChatService(Runnable::run, settings(16)); }

    private static void joinMessageAndLeave() {
        ChatService service = direct();
        FakeConnection a = new FakeConnection("a", "alice");
        FakeConnection b = new FakeConnection("b", "bob");
        service.connected(a);
        service.connected(b);
        require("WELCOME alice".equals(a.last()), "환영 응답 누락");
        service.handle(a, "JOIN lobby");
        service.handle(b, "JOIN lobby");
        require(a.sent.contains("JOINED lobby bob"), "기존 참여자가 입장 알림을 받지 못했습니다.");
        service.handle(a, "SAY m1 안녕하세요");
        require(b.sent.contains("MSG lobby alice m1 안녕하세요"), "방송 누락");
        require("ACK m1".equals(a.last()), "확인 응답 누락");
        service.disconnected(a);
        require("LEFT lobby alice".equals(b.last()), "퇴장 알림 누락");
        require(service.metricsLine().contains("users=1 rooms=1"), "퇴장 뒤 상태 불일치");
    }

    private static void duplicateAfterReconnect() {
        ChatService service = direct();
        FakeConnection a = new FakeConnection("a1", "alice");
        FakeConnection b = new FakeConnection("b", "bob");
        service.connected(a);
        service.connected(b);
        service.handle(a, "JOIN lobby");
        service.handle(b, "JOIN lobby");
        service.handle(a, "SAY m1 hi");
        // ACK를 받기 전에 연결이 끊겼다고 보고 새 연결에서 같은 ID로 다시 보냅니다.
        service.disconnected(a);
        FakeConnection again = new FakeConnection("a2", "alice");
        service.connected(again);
        service.handle(again, "JOIN lobby");
        service.handle(again, "SAY m1 hi");
        require("ACK m1".equals(again.last()), "재전송에 확인 응답이 없습니다.");
        long copies = b.sent.stream().filter(line -> line.equals("MSG lobby alice m1 hi")).count();
        require(copies == 1, "같은 메시지를 두 번 방송했습니다: " + copies);
        require(service.metricsLine().contains("duplicates=1"), "중복 카운터 불일치");
    }

    private static void replacedConnection() {
        ChatService service = direct();
        FakeConnection old = new FakeConnection("old", "alice");
        FakeConnection fresh = new FakeConnection("new", "alice");
        service.connected(old);
        service.connected(fresh);
        require(old.closedCode == 4000, "이전 연결을 대체 코드로 닫지 않았습니다.");
        // 이전 연결의 종료 사건이 늦게 도착해도 새 연결은 그대로 남아야 합니다.
        service.disconnected(old);
        service.handle(old, "JOIN lobby");
        require(old.sent.size() == 1, "대체된 연결의 늦은 명령을 처리했습니다.");
        service.handle(fresh, "JOIN lobby");
        require("JOINED lobby alice".equals(fresh.last()), "새 연결이 정리되었습니다.");
    }

    private static void invalidInputAndLimits() {
        ChatService service = direct();
        FakeConnection a = new FakeConnection("a", "alice");
        service.connected(a);
        service.handle(a, "DANCE");
        require("ERROR unknown_command".equals(a.last()), "알 수 없는 명령");
        service.handle(a, "SAY m1 hi");
        require("ERROR not_in_room m1".equals(a.last()), "방 밖의 메시지");
        service.handle(a, "JOIN Lobby!");
        require("ERROR bad_room".equals(a.last()), "방 이름 검증");
        service.handle(a, "JOIN r1");
        service.handle(a, "SAY m2 " + "가".repeat(11));
        require("ERROR bad_text m2".equals(a.last()), "UTF-8 바이트 한도(33 > 32)");
        service.handle(a, "SAY m3 a\u0007b");
        require("ERROR bad_text m3".equals(a.last()), "제어 문자 검증");

        FakeConnection b = new FakeConnection("b", "bob");
        FakeConnection c = new FakeConnection("c", "carol");
        service.connected(b);
        service.connected(c);
        service.handle(b, "JOIN r1");
        service.handle(c, "JOIN r1");
        require("ERROR room_full r1".equals(c.last()), "방 인원 한도");
        service.handle(c, "JOIN r2");
        FakeConnection d = new FakeConnection("d", "dave");
        service.connected(d);
        service.handle(d, "JOIN r3");
        require("ERROR room_limit r3".equals(d.last()), "방 개수 한도");

        // 오류가 누적되면 정책 위반(1008)으로 연결을 닫습니다. 앞에서 이미 5번 오류를 냈습니다.
        for (int i = 0; i < 5; i++) service.handle(a, "DANCE");
        require(a.closedCode == 1008, "반복 오류 연결을 닫지 않았습니다.");
    }

    private static void overloadAndDraining() throws InterruptedException {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        // 보류 익스큐터: 작업을 큐에만 넣어 두고 원하는 시점에 꺼내 실행합니다.
        ChatService service = new ChatService(queue::add, settings(2));
        require(service.submit(() -> { }), "첫 명령 거부");
        require(service.submit(() -> { }), "둘째 명령 거부");
        require(!service.submit(() -> { }), "대기 한도를 넘은 명령을 받았습니다.");
        while (!queue.isEmpty()) queue.poll().run();
        require(service.submit(() -> { }), "처리 뒤에도 접수가 회복되지 않았습니다.");
        while (!queue.isEmpty()) queue.poll().run();

        ChatService draining = direct();
        FakeConnection a = new FakeConnection("a", "alice");
        draining.connected(a);
        draining.startDraining();
        require("NOTICE server_shutting_down".equals(a.last()), "종료 공지 누락");
        require(!draining.submit(() -> { }), "종료 중 새 명령을 받았습니다.");
        require(draining.awaitIdle(100), "접수한 명령의 실행 완료를 확인하지 못했습니다.");
    }

    private static void slowClient() throws InterruptedException {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(8, 16));
        LongAdder dropped = new LongAdder();
        WebSocketConnection connection = new WebSocketConnection(channel, "alice", 100, dropped);
        try {
            // flush하지 않은 데이터로 높은 수위를 넘겨 상대가 읽지 않는 상황을 흉내 냅니다.
            channel.write(Unpooled.wrappedBuffer(new byte[32]));
            require(!channel.isWritable(), "쓰기 불가 상태를 만들지 못했습니다.");
            require(!connection.send("first"), "쓰기 불가 상태에서 보냈습니다.");
            channel.flush();
            require(connection.send("second"), "쓰기 가능 상태로 회복하지 못했습니다.");
            ReferenceCountUtil.release(channel.readOutbound());
            require("NOTICE dropped 1".equals(text(channel.readOutbound())), "폐기 알림 누락");
            require("second".equals(text(channel.readOutbound())), "회복 뒤 메시지 누락");

            channel.write(Unpooled.wrappedBuffer(new byte[32]));
            connection.send("third");
            Thread.sleep(150);
            connection.send("fourth");
            require(!channel.isOpen(), "느린 사용자 제한 시간이 지나도 연결이 열려 있습니다.");
            require(dropped.sum() == 3, "폐기 카운터 불일치: " + dropped.sum());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void transportBoundary() {
        ChatService service = direct();
        ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        EmbeddedChannel channel = new EmbeddedChannel(new ChatFrameHandler(service, settings(16), group));
        try {
            // 핸드셰이크 전 데이터 프레임은 서비스로 넘기지 않습니다.
            channel.writeInbound(new TextWebSocketFrame("JOIN lobby"));
            require(channel.readOutbound() == null, "핸드셰이크 전 프레임을 처리했습니다.");
            channel.attr(ChatFrameHandler.USER).set("alice");
            channel.pipeline().fireUserEventTriggered(new WebSocketServerProtocolHandler.HandshakeComplete(
                    "/ws", new DefaultHttpHeaders(), null));
            require("WELCOME alice".equals(text(channel.readOutbound())), "서비스 등록 누락");
            channel.writeInbound(new TextWebSocketFrame("JOIN lobby"));
            require("JOINED lobby alice".equals(text(channel.readOutbound())), "명령 전달 누락");
            channel.writeInbound(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[] {1, 2})));
            Object close = channel.readOutbound();
            try {
                require(close instanceof CloseWebSocketFrame frame && frame.statusCode() == 1003,
                        "바이너리 프레임을 1003으로 거부하지 않았습니다.");
            } finally {
                ReferenceCountUtil.release(close);
            }
            require(!channel.isOpen(), "거부 뒤 연결이 열려 있습니다.");
            channel.runPendingTasks();
            require(service.metricsLine().contains("users=0 rooms=0"), "연결 종료 뒤 서비스 상태가 남았습니다.");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    // 꺼낸 프레임의 문자열을 읽고 바로 해제합니다.
    private static String text(Object message) {
        try {
            return message instanceof TextWebSocketFrame frame ? frame.text() : null;
        } finally {
            ReferenceCountUtil.release(message);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    // 보낸 줄과 종료 코드를 기록하는 가짜 연결입니다. close는 종료 사건을 스스로 만들지 않습니다.
    private static final class FakeConnection implements ChatConnection {
        private final String id;
        private final String user;
        private final List<String> sent = new ArrayList<>();
        private int closedCode;

        FakeConnection(String id, String user) {
            this.id = id;
            this.user = user;
        }

        @Override
        public String id() { return id; }

        @Override
        public String user() { return user; }

        @Override
        public boolean send(String line) {
            if (closedCode != 0) return false;
            sent.add(line);
            return true;
        }

        @Override
        public void close(int code, String reason) { closedCode = code; }

        String last() { return sent.isEmpty() ? null : sent.get(sent.size() - 1); }
    }
}
