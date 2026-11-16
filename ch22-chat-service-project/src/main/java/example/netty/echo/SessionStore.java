package example.netty.echo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

// 접속 토큰을 사용자 이름과 만료 시각에 연결합니다. 여러 이벤트 루프가 동시에 조회합니다.
public final class SessionStore {
    private static final Pattern USER = Pattern.compile("[a-z0-9_-]{2,20}");
    private static final int MAX_SESSIONS = 10_000;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final byte[] password;
    private final long ttlNanos;

    private record Session(String user, long expiresAt) { }

    public SessionStore(String password, long ttlMillis) {
        this.password = password.getBytes(StandardCharsets.UTF_8);
        this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(ttlMillis);
    }

    // 성공하면 새 토큰을, 실패하면 null을 반환합니다. 입력한 암호는 저장하거나 기록하지 않습니다.
    public String login(String user, String password) {
        if (user == null || password == null) return null;
        boolean passwordOk = MessageDigest.isEqual(this.password, password.getBytes(StandardCharsets.UTF_8));
        if (!passwordOk || !USER.matcher(user).matches()) return null;
        purgeExpired();
        // 세션수 제한입니다.
        if (sessions.size() >= MAX_SESSIONS) return null;
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(user, System.nanoTime() + ttlNanos));
        return token;
    }

    // 유효한 토큰이면 사용자 이름을, 없거나 만료되었으면 null을 반환합니다.
    public String user(String token) {
        if (token == null) return null;
        Session session = sessions.get(token);
        if (session == null) return null;
        if (System.nanoTime() - session.expiresAt() > 0) {
            sessions.remove(token, session);
            return null;
        }
        return session.user();
    }

    public void purgeExpired() {
        long now = System.nanoTime();
        sessions.values().removeIf(session -> now - session.expiresAt() > 0);
    }

    public int size() { return sessions.size(); }
}
