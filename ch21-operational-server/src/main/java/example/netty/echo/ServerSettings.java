package example.netty.echo;

// 값이 없을 때만 기본값을 쓰고, 잘못된 숫자는 조용히 고치지 않고 시작을 실패시킵니다.
public record ServerSettings(String host, int port, int maxConnections,
                             int maxInFlight, long drainMillis) {
    public static ServerSettings fromEnvironment() {
        String host = System.getenv().getOrDefault("NETTY_BIND_HOST", "127.0.0.1");
        if (host.isBlank()) throw new IllegalArgumentException("NETTY_BIND_HOST");
        return new ServerSettings(host,
                number("NETTY_PORT", 8000, 1, 65535),
                number("NETTY_MAX_CONNECTIONS", 100, 1, 10000),
                number("NETTY_MAX_IN_FLIGHT", 128, 1, 10000),
                number("NETTY_DRAIN_MILLIS", 5000, 0, 60000));
    }

    private static int number(String name, int fallback, int min, int max) {
        String text = System.getenv(name);
        int value = text == null ? fallback : Integer.parseInt(text);
        if (value < min || value > max) throw new IllegalArgumentException(name + " 범위 오류");
        return value;
    }
}
