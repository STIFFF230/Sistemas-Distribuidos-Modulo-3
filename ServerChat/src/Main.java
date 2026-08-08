import chat.ChatServer;
import chat.ServerConfig;

public class Main {
    public static void main(String[] args) {
        int puerto = args.length > 0 ? Integer.parseInt(args[0]) : ServerConfig.DEFAULT_PORT;
        new ChatServer(new ServerConfig(puerto)).start();
    }
}
