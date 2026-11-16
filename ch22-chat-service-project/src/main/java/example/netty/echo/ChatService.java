package example.netty.echo;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

// 사용자·채팅방 상태를 하나의 서비스 실행기 안에서만 바꿉니다. Netty 채널 타입에 의존하지 않습니다.
public final class ChatService {
    private static final System.Logger LOG = System.getLogger(ChatService.class.getName());
    private static final Pattern ROOM = Pattern.compile("[a-z0-9_-]{1,20}");
    private static final Pattern MESSAGE_ID = Pattern.compile("[A-Za-z0-9-]{1,40}");
    private static final int RECENT_IDS = 256;
    private static final int REMEMBERED_USERS = 10_000;
    private static final int MAX_ERRORS = 10;

    private final Executor executor;
    private final ChatSettings settings;
    private final LongAdder messages = new LongAdder();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder dropped = new LongAdder();

    // 접수 상태는 여러 이벤트 루프가 함께 보므로 짧은 잠금으로 보호합니다.
    private final Object admission = new Object();
    private boolean draining;
    private int pending;

    // 아래 상태는 executor가 실행하는 작업 안에서만 읽고 씁니다.
    private final Map<ChatConnection, Member> members = new HashMap<>();
    private final Map<String, Member> byUser = new HashMap<>();
    private final Map<String, Set<Member>> rooms = new HashMap<>();
    // 재연결 뒤 같은 메시지 ID가 다시 와도 한 번만 방송하도록 사용자별 최근 ID를 기억합니다.
    private final Map<String, Set<String>> recentIds = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
            return size() > REMEMBERED_USERS;
        }
    };

    private static final class Member {
        private final ChatConnection connection;
        private String room;
        private int errors;

        Member(ChatConnection connection) { this.connection = connection; }

        String user() { return connection.user(); }
    }

    public ChatService(Executor executor, ChatSettings settings) {
        this.executor = executor;
        this.settings = settings;
    }

    static boolean isMessageId(String text) { return MESSAGE_ID.matcher(text).matches(); }

    // 일반적인 명령의 실행 지점입니다. 종료 중이거나 대기 명령이 한도에 이르면 거부합니다
    public boolean submit(Runnable command) {
        synchronized (admission) {
            if (draining || pending >= settings.maxPending()) {
                rejected.increment();
                return false;
            }
            pending++;
            try {
                executor.execute(() -> run(command));
            } catch (RejectedExecutionException cause) {
                pending--;
                rejected.increment();
                return false;
            }
            return true;
        }
    }

    private void run(Runnable command) {
        try {
            command.run();
        } catch (RuntimeException cause) {
            // 한 명령의 결함이 서비스 실행기 전체를 멈추지 않도록 기록하고 다음 작업으로 넘어갑니다.
            LOG.log(System.Logger.Level.ERROR, "event=command_failed cause=" + cause, cause);
        } finally {
            synchronized (admission) { pending--; }
        }
    }

    // 연결 정리는 한도나 종료 상태와 관계없이 실행해야 서비스 상태가 남지 않습니다.
    public void cleanup(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException cause) {
            // 서비스 실행기까지 종료된 뒤라면 정리할 상태도 함께 사라집니다.
            LOG.log(System.Logger.Level.DEBUG, "event=cleanup_skipped");
        }
    }

    public void startDraining() {
        synchronized (admission) { draining = true; }
        cleanup(() -> {
            for (Member member : members.values()) member.connection.send("NOTICE server_shutting_down");
        });
    }

    public boolean draining() { synchronized (admission) { return draining; } }

    // startDraining 뒤에 호출합니다.
    public boolean awaitIdle(long millis) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        cleanup(done::countDown);
        return done.await(millis, TimeUnit.MILLISECONDS);
    }

    public LongAdder droppedCounter() { return dropped; }

    // 서비스 실행기에서 호출합니다. 누적 카운터는 초당 수치가 아닙니다.
    public String metricsLine() {
        int waiting;
        synchronized (admission) { waiting = pending; }
        return "event=metrics users=" + byUser.size() + " rooms=" + rooms.size()
                + " pending=" + waiting + " messages=" + messages.sum()
                + " duplicates=" + duplicates.sum() + " dropped=" + dropped.sum()
                + " rejected=" + rejected.sum();
    }

    public void connected(ChatConnection connection) {
        Member previous = byUser.get(connection.user());
        if (previous != null) {
            // 같은 사용자의 새 연결이 이전 연결을 대체합니다. 4000은 이 서비스에서는 종료 코드를 말합니다.
            removeFromRoom(previous);
            members.remove(previous.connection);
            previous.connection.close(4000, "replaced");
            LOG.log(System.Logger.Level.INFO, "event=connection_replaced user=" + connection.user()
                    + " old=" + previous.connection.id() + " new=" + connection.id());
        }
        Member member = new Member(connection);
        members.put(connection, member);
        byUser.put(connection.user(), member);
        connection.send("WELCOME " + connection.user());
    }

    public void disconnected(ChatConnection connection) {
        Member member = members.remove(connection);
        // 이미 새 연결로 대체되어 정리된 연결이면 새 연결의 상태를 건드리지 않습니다.
        if (member == null) return;
        byUser.remove(member.user(), member);
        removeFromRoom(member);
    }

    public void handle(ChatConnection connection, String line) {
        Member member = members.get(connection);
        // 대체된 연결에서 늦게 도착한 명령은 무시합니다.
        if (member == null) return;
        String[] parts = line.split(" ", 3);
        switch (parts[0]) {
            case "JOIN" -> {
                if (parts.length == 2) join(member, parts[1]);
                else error(member, "bad_command", null);
            }
            case "LEAVE" -> {
                if (parts.length == 1) removeFromRoom(member);
                else error(member, "bad_command", null);
            }
            case "SAY" -> say(member, parts);
            default -> error(member, "unknown_command", null);
        }
    }

    private void join(Member member, String room) {
        if (!ROOM.matcher(room).matches()) {
            error(member, "bad_room", null);
            return;
        }
        if (room.equals(member.room)) {
            // 재연결 과정에서 같은 JOIN을 다시 보내도 같은 결과를 돌려줍니다.
            member.connection.send("JOINED " + room + " " + member.user());
            return;
        }
        Set<Member> target = rooms.get(room);
        if (target == null && rooms.size() >= settings.maxRooms()) {
            error(member, "room_limit", room);
            return;
        }
        if (target != null && target.size() >= settings.maxRoomMembers()) {
            error(member, "room_full", room);
            return;
        }
        removeFromRoom(member);
        rooms.computeIfAbsent(room, key -> new LinkedHashSet<>()).add(member);
        member.room = room;
        broadcast(room, "JOINED " + room + " " + member.user());
        LOG.log(System.Logger.Level.INFO, "event=room_joined room=" + room
                + " user=" + member.user() + " channel=" + member.connection.id());
    }

    private void removeFromRoom(Member member) {
        if (member.room == null) return;
        String room = member.room;
        Set<Member> current = rooms.get(room);
        // 떠나는 사용자도 LEFT를 받도록 제거하기 전에 방송합니다.
        broadcast(room, "LEFT " + room + " " + member.user());
        current.remove(member);
        member.room = null;
        if (current.isEmpty()) rooms.remove(room);
    }

    private void say(Member member, String[] parts) {
        if (parts.length != 3 || !isMessageId(parts[1])) {
            error(member, "bad_command", null);
            return;
        }
        String id = parts[1];
        String text = parts[2];
        if (member.room == null) {
            error(member, "not_in_room", id);
            return;
        }
        if (!validText(text)) {
            error(member, "bad_text", id);
            return;
        }
        Set<String> seen = recentIds.computeIfAbsent(member.user(), key -> new LinkedHashSet<>());
        if (seen.contains(id)) {
            // 이미 방송한 메시지입니다. 다시 방송하지 않고 확인 응답만 반복합니다.
            duplicates.increment();
            member.connection.send("ACK " + id);
            return;
        }
        seen.add(id);
        if (seen.size() > RECENT_IDS) {
            Iterator<String> oldest = seen.iterator();
            oldest.next();
            oldest.remove();
        }
        messages.increment();
        broadcast(member.room, "MSG " + member.room + " " + member.user() + " " + id + " " + text);
        member.connection.send("ACK " + id);
    }

    private boolean validText(String text) {
        if (text.isBlank()) return false;
        if (text.getBytes(StandardCharsets.UTF_8).length > settings.maxMessageBytes()) return false;
        return text.codePoints().noneMatch(Character::isISOControl);
    }

    private void error(Member member, String code, String reference) {
        member.connection.send("ERROR " + code + (reference == null ? "" : " " + reference));
        if (++member.errors >= MAX_ERRORS) {
            LOG.log(System.Logger.Level.WARNING, "event=too_many_errors user=" + member.user()
                    + " channel=" + member.connection.id());
            member.connection.close(1008, "too many errors");
        }
    }

    private void broadcast(String room, String line) {
        for (Member member : rooms.get(room)) member.connection.send(line);
    }
}
