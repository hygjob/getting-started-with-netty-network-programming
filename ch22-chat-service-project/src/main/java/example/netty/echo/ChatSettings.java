package example.netty.echo;

// 채팅 서비스의 한도와 운영 설정입니다. 잘못된 값은 시작 전에 거부합니다.
public record ChatSettings(String host, int port, boolean tls, String password,
                           int maxConnections, int maxRooms, int maxRoomMembers,
                           int maxMessageBytes, int maxPending, long slowMillis,
                           long sessionMillis, long drainMillis) {
    public ChatSettings {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("NETTY_BIND_HOST");
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException("NETTY_CHAT_PASSWORD를 12자 이상으로 설정하십시오.");
        }
    }

    public static ChatSettings fromEnvironment() {
        boolean tls = flag("NETTY_TLS");
        return new ChatSettings(
                System.getenv().getOrDefault("NETTY_BIND_HOST", "127.0.0.1"),
                number("NETTY_PORT", tls ? 8443 : 8080, 1, 65535),
                tls,
                System.getenv("NETTY_CHAT_PASSWORD"),
                number("NETTY_MAX_CONNECTIONS", 200, 1, 10000),
                number("NETTY_MAX_ROOMS", 50, 1, 1000),
                number("NETTY_MAX_ROOM_MEMBERS", 50, 2, 1000),
                number("NETTY_MAX_MESSAGE_BYTES", 1024, 16, 8192),
                number("NETTY_MAX_PENDING", 1024, 1, 100000),
                number("NETTY_SLOW_MILLIS", 5000, 100, 60000),
                number("NETTY_SESSION_MINUTES", 30, 1, 1440) * 60_000L,
                number("NETTY_DRAIN_MILLIS", 3000, 0, 60000));
    }

    private static boolean flag(String name) {
        String text = System.getenv(name);
        if (text == null || text.equals("false")) return false;
        if (text.equals("true")) return true;
        throw new IllegalArgumentException(name + "는 true 또는 false여야 합니다.");
    }

    private static int number(String name, int fallback, int min, int max) {
        String text = System.getenv(name);
        int value = text == null ? fallback : Integer.parseInt(text);
        if (value < min || value > max) throw new IllegalArgumentException(name + " 범위 오류");
        return value;
    }

    // 레코드의 기본 toString은 모든 필드를 출력하므로 비밀 값을 가립니다.
    @Override
    public String toString() {
        return "ChatSettings[host=" + host + ", port=" + port + ", tls=" + tls
                + ", maxConnections=" + maxConnections + ", maxPending=" + maxPending + ", password=***]";
    }
}
