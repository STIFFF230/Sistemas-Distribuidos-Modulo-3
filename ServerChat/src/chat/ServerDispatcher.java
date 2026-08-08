package chat;

import chat.protocol.Messages;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Consumidor único de la cola global de eventos. Valida, asigna 'sequence',
 * mantiene el mapa username -> ClientSession y enruta mensajes grupales y
 * privados. Al ser el único hilo que modifica el mapa de sesiones, evita
 * condiciones de carrera sin necesidad de bloqueos adicionales.
 */
final class ServerDispatcher implements Runnable {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    private final BlockingQueue<ClientEvent> events;
    private final ConcurrentMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    ServerDispatcher(BlockingQueue<ClientEvent> events) {
        this.events = events;
    }

    @Override
    public void run() {
        while (true) {
            ClientEvent event;
            try {
                event = events.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                dispatch(event);
            } catch (RuntimeException e) {
                log("Error procesando evento: " + e.getMessage());
            }
        }
    }

    private void dispatch(ClientEvent event) {
        switch (event.kind()) {
            case MESSAGE -> handleMessage(event.session(), event.message());
            case MALFORMED -> event.session().enqueue(Messages.error(event.errorText()));
            case DISCONNECTED -> handleDisconnect(event.session());
        }
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
            case "DISCONNECT" -> handleDisconnect(session);
            case "REGISTER" -> session.enqueue(Messages.error("Ya está registrado como " + session.username() + "."));
            default -> session.enqueue(Messages.error("Tipo de mensaje desconocido: " + type));
        }
    }

    private void handleRegister(ClientSession session, Map<String, Object> msg) {
        String username = stringField(msg, "username");
        if (!isValidUsername(username)) {
            session.enqueue(Messages.registerError(
                    "El nombre debe tener entre " + ServerConfig.MIN_USERNAME_LENGTH + " y "
                            + ServerConfig.MAX_USERNAME_LENGTH + " caracteres (letras, números, guion y guion bajo)."));
            return;
        }
        ClientSession existing = sessions.putIfAbsent(username, session);
        if (existing != null) {
            session.enqueue(Messages.registerError("El nombre de usuario ya está en uso"));
            return;
        }
        session.setUsername(username);
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
                session.username(), text.trim(), now(), sequence.incrementAndGet());
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
                session.username(), to, text.trim(), now(), sequence.incrementAndGet());
        session.enqueue(out);
        if (recipient != session) {
            recipient.enqueue(out);
        }
    }

    private void handleDisconnect(ClientSession session) {
        if (!session.markClosed()) {
            return; // Ya se limpió (pudo llegar tanto del reader como del writer).
        }
        session.closeAndInterruptWriter();
        String username = session.username();
        if (username != null) {
            sessions.remove(username, session);
            broadcastAll(Messages.userLeft(username));
            broadcastAll(Messages.userList(currentUsernames()));
            log("Usuario desconectado: " + username);
        } else {
            log("Conexión cerrada antes de registrarse: " + session.remoteAddress());
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
