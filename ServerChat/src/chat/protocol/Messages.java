package chat.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fábricas para los mensajes salientes definidos en PROTOCOL.md. */
public final class Messages {
    private Messages() {
    }

    public static Map<String, Object> registerOk(String username) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "REGISTER_OK");
        m.put("username", username);
        return m;
    }

    public static Map<String, Object> registerError(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "REGISTER_ERROR");
        m.put("message", message);
        return m;
    }

    public static Map<String, Object> groupMessage(String from, String text, String timestamp, long sequence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "GROUP_MESSAGE");
        m.put("from", from);
        m.put("text", text);
        m.put("timestamp", timestamp);
        m.put("sequence", sequence);
        return m;
    }

    public static Map<String, Object> privateMessage(String from, String to, String text, String timestamp, long sequence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "PRIVATE_MESSAGE");
        m.put("from", from);
        m.put("to", to);
        m.put("text", text);
        m.put("timestamp", timestamp);
        m.put("sequence", sequence);
        return m;
    }

    public static Map<String, Object> userList(List<String> users) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "USER_LIST");
        m.put("users", users);
        return m;
    }

    public static Map<String, Object> userJoined(String username) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "USER_JOINED");
        m.put("username", username);
        return m;
    }

    public static Map<String, Object> userLeft(String username) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "USER_LEFT");
        m.put("username", username);
        return m;
    }

    public static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "ERROR");
        m.put("message", message);
        return m;
    }
}
