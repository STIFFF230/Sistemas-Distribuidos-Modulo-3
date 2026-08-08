package chat;

public final class ServerConfig {
    public static final int DEFAULT_PORT = 5000;
    public static final int MAX_MESSAGE_BYTES = 8192;
    public static final int MIN_USERNAME_LENGTH = 3;
    public static final int MAX_USERNAME_LENGTH = 20;

    private final int port;

    public ServerConfig(int port) {
        this.port = port;
    }

    public int port() {
        return port;
    }

    public int maxMessageBytes() {
        return MAX_MESSAGE_BYTES;
    }
}
