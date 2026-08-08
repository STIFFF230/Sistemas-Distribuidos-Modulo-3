package chat;

import java.util.Map;

/** Evento producido por un ClientReader y consumido por el ServerDispatcher. */
final class ClientEvent {
    enum Kind { MESSAGE, MALFORMED, DISCONNECTED }

    private final ClientSession session;
    private final Kind kind;
    private final Map<String, Object> message;
    private final String errorText;

    private ClientEvent(ClientSession session, Kind kind, Map<String, Object> message, String errorText) {
        this.session = session;
        this.kind = kind;
        this.message = message;
        this.errorText = errorText;
    }

    static ClientEvent message(ClientSession session, Map<String, Object> message) {
        return new ClientEvent(session, Kind.MESSAGE, message, null);
    }

    static ClientEvent malformed(ClientSession session, String errorText) {
        return new ClientEvent(session, Kind.MALFORMED, null, errorText);
    }

    static ClientEvent disconnected(ClientSession session) {
        return new ClientEvent(session, Kind.DISCONNECTED, null, null);
    }

    ClientSession session() {
        return session;
    }

    Kind kind() {
        return kind;
    }

    Map<String, Object> message() {
        return message;
    }

    String errorText() {
        return errorText;
    }
}
