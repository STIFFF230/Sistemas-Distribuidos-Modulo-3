package chat;

import chat.protocol.Json;
import chat.protocol.JsonParseException;
import chat.protocol.Messages;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lógica de negocio del chat: registro, mensajes de grupo y privados,
 * desconexión. ChatServer invoca onLine()/onDisconnected() siempre desde su
 * único hilo selector, nunca desde dos hilos a la vez -- por eso 'sessions'
 * es un HashMap normal (no ConcurrentHashMap) y 'sequence' es un long
 * cualquiera (no AtomicLong): no existe la condición de carrera que esas
 * estructuras existen para resolver, porque nunca hay dos hilos escribiendo
 * aquí al mismo tiempo. Contrastar con ServerStats, que sí necesita un
 * candado explícito porque a esa sí la tocan dos hilos distintos.
 */
final class ServerDispatcher {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    private final Map<String, ClientSession> sessions = new HashMap<>();
    private final ServerStats stats;
    private long sequence = 0;

    ServerDispatcher(ServerStats stats) {
        this.stats = stats;
    }

    /** Una línea completa (un JSON) ya extraída por LineAccumulator. */
    void onLine(ClientSession session, String line) {
        Map<String, Object> message;
        try {
            message = Json.parseObject(line);
        } catch (JsonParseException e) {
            session.enqueue(Messages.error("JSON inválido: " + e.getMessage()));
            return;
        }
        if (!(message.get("type") instanceof String)) {
            session.enqueue(Messages.error("Falta el campo 'type'."));
            return;
        }
        handleMessage(session, message);
    }

    /** La línea superó el tamaño máximo permitido (ver ServerConfig). */
    void onMalformed(ClientSession session, String errorText) {
        session.enqueue(Messages.error(errorText));
    }

    void onDisconnected(ClientSession session) {
        if (!session.markClosed()) {
            return; // ya se limpió antes
        }
        stats.clientDisconnected();
        String username = session.username();
        if (username != null) {
            sessions.remove(username, session);
            stats.userUnregistered();
            broadcastAll(Messages.userLeft(username));
            broadcastAll(Messages.userList(currentUsernames()));
            log("Usuario desconectado: " + username);
        } else {
            log("Conexión cerrada antes de registrarse: " + session.remoteAddress());
        }
    }

    void onConnected(ClientSession session) {
        stats.clientConnected();
    }

    private void handleMessage(ClientSession session, Map<String, Object> msg) {
        String type = (String) msg.get("type");
        if (!session.isRegistered()) {
            if ("REGISTER".equals(type)) {
                handleRegister(session, msg);
            } else {
                session.enqueue(Messages.error("Debe registrarse antes de enviar mensajes."));
            }
            return;
        }
        switch (type) {
            case "GROUP_MESSAGE" -> handleGroupMessage(session, msg);
            case "PRIVATE_MESSAGE" -> handlePrivateMessage(session, msg);
            case "DISCONNECT" -> requestDisconnect(session);
            case "REGISTER" -> session.enqueue(Messages.error("Ya está registrado como " + session.username() + "."));
            default -> session.enqueue(Messages.error("Tipo de mensaje desconocido: " + type));
        }
    }

    private void requestDisconnect(ClientSession session) {
        session.closeChannel();
        onDisconnected(session);
    }

    private void handleRegister(ClientSession session, Map<String, Object> msg) {
        String username = stringField(msg, "username");
        if (!isValidUsername(username)) {
            session.enqueue(Messages.registerError(
                    "El nombre debe tener entre " + ServerConfig.MIN_USERNAME_LENGTH + " y "
                            + ServerConfig.MAX_USERNAME_LENGTH + " caracteres (letras, números, guion y guion bajo)."));
            return;
        }
        if (sessions.containsKey(username)) {
            session.enqueue(Messages.registerError("El nombre de usuario ya está en uso"));
            return;
        }
        sessions.put(username, session);
        session.setUsername(username);
        stats.userRegistered();
        session.enqueue(Messages.registerOk(username));
        session.enqueue(Messages.userList(currentUsernames()));
        broadcastExcept(session, Messages.userJoined(username));
        log("Usuario registrado: " + username + " (" + session.remoteAddress() + ")");
    }

    private void handleGroupMessage(ClientSession session, Map<String, Object> msg) {
        String text = stringField(msg, "text");
        if (!isValidText(text)) {
            session.enqueue(Messages.error("El mensaje no puede estar vacío ni superar 8 KB."));
            return;
        }
        Map<String, Object> out = Messages.groupMessage(
                session.username(), text.trim(), now(), ++sequence);
        stats.groupMessageSent();
        broadcastAll(out);
    }

    private void handlePrivateMessage(ClientSession session, Map<String, Object> msg) {
        String to = stringField(msg, "to");
        String text = stringField(msg, "text");
        if (!isValidText(text)) {
            session.enqueue(Messages.error("El mensaje no puede estar vacío ni superar 8 KB."));
            return;
        }
        ClientSession recipient = to == null ? null : sessions.get(to);
        if (recipient == null) {
            session.enqueue(Messages.error("El usuario '" + to + "' no está conectado."));
            return;
        }
        Map<String, Object> out = Messages.privateMessage(
                session.username(), to, text.trim(), now(), ++sequence);
        stats.privateMessageSent();
        session.enqueue(out);
        if (recipient != session) {
            recipient.enqueue(out);
        }
    }

    private void broadcastAll(Map<String, Object> message) {
        for (ClientSession s : sessions.values()) {
            s.enqueue(message);
        }
    }

    private void broadcastExcept(ClientSession excluded, Map<String, Object> message) {
        for (ClientSession s : sessions.values()) {
            if (s != excluded) {
                s.enqueue(message);
            }
        }
    }

    private List<String> currentUsernames() {
        List<String> users = new ArrayList<>(sessions.keySet());
        users.sort(String::compareTo);
        return users;
    }

    private static boolean isValidUsername(String username) {
        return username != null
                && username.length() >= ServerConfig.MIN_USERNAME_LENGTH
                && username.length() <= ServerConfig.MAX_USERNAME_LENGTH
                && USERNAME_PATTERN.matcher(username).matches();
    }

    private static boolean isValidText(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return false;
        return trimmed.getBytes(StandardCharsets.UTF_8).length <= ServerConfig.MAX_MESSAGE_BYTES;
    }

    private static String stringField(Map<String, Object> msg, String field) {
        Object value = msg.get(field);
        return value instanceof String s ? s : null;
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static void log(String message) {
        System.out.println("[ChatServer] " + message);
    }
}
