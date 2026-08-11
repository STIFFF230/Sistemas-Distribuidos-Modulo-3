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
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Lógica de negocio del chat: registro, mensajes de grupo y privados,
 * desconexión.
 *
 * A diferencia de la primera versión (todo corría en el hilo selector),
 * ahora onLine() puede invocarse desde CUALQUIER hilo del WorkerPool -- en
 * paralelo real entre clientes distintos, aunque nunca dos veces a la vez
 * para el MISMO cliente (eso lo garantiza la compuerta CAS de
 * ClientSession.scheduled, en ChatServer). Por eso 'sessions' (el mapa de
 * usuarios conectados) y 'sequence' (el número de orden de los mensajes)
 * SÍ necesitan protegerse con un candado explícito: dos workers procesando
 * clientes distintos pueden intentar registrar un usuario, transmitir un
 * mensaje o asignar 'sequence' al mismo tiempo. sessionsLock es ese
 * candado -- se toma al principio de cada método que toca 'sessions' o
 * 'sequence', y se libera siempre en un finally.
 */
final class ServerDispatcher {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    private final ReentrantLock sessionsLock = new ReentrantLock();
    private final Map<String, ClientSession> sessions = new HashMap<>();
    private final ChatActivityTracker activityTracker;
    private long sequence = 0;

    ServerDispatcher(ChatActivityTracker activityTracker) {
        this.activityTracker = activityTracker;
    }

    /** Una línea completa (un JSON) ya extraída por LineAccumulator. Puede
     * correr en cualquier hilo del WorkerPool. */
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

    /** Puede llamarse desde el hilo selector (socket roto/cerrado) o desde
     * un worker (mensaje DISCONNECT) -- por eso todo lo que toca aquí está
     * protegido por sessionsLock, sin importar qué hilo la invoque. */
    void onDisconnected(ClientSession session) {
        if (!session.markClosed()) {
            return; // ya se limpió antes (posible desde dos hilos a la vez)
        }
        String username = session.username();
        if (username == null) {
            log("Conexión cerrada antes de registrarse: " + session.remoteAddress());
            return;
        }
        sessionsLock.lock();
        try {
            sessions.remove(username, session);
            activityTracker.remove(username);
            broadcastAll(Messages.userLeft(username));
            broadcastAll(Messages.userList(currentUsernames()));
        } finally {
            sessionsLock.unlock();
        }
        log("Usuario desconectado: " + username);
    }

    void onConnected(ClientSession session) {
        // Nada que hacer aquí: la sesión se registra en 'sessions' solo
        // cuando el cliente completa REGISTER (ver handleRegister).
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
        sessionsLock.lock();
        try {
            if (sessions.containsKey(username)) {
                session.enqueue(Messages.registerError("El nombre de usuario ya está en uso"));
                return;
            }
            sessions.put(username, session);
            session.setUsername(username);
            activityTracker.recordActivity(username); // arranca su reloj de inactividad
            session.enqueue(Messages.registerOk(username));
            session.enqueue(Messages.userList(currentUsernames()));
            broadcastExcept(session, Messages.userJoined(username));
        } finally {
            sessionsLock.unlock();
        }
        log("Usuario registrado: " + username + " (" + session.remoteAddress() + ")");
    }

    private void handleGroupMessage(ClientSession session, Map<String, Object> msg) {
        String text = stringField(msg, "text");
        if (!isValidText(text)) {
            session.enqueue(Messages.error("El mensaje no puede estar vacío ni superar 8 KB."));
            return;
        }
        sessionsLock.lock();
        try {
            Map<String, Object> out = Messages.groupMessage(
                    session.username(), text.trim(), now(), ++sequence);
            broadcastAll(out);
        } finally {
            sessionsLock.unlock();
        }
        activityTracker.recordActivity(session.username()); // acaba de hablar en el grupo
    }

    /**
     * Llamado por ChatServer cuando PresenceMonitor detecta usuarios que
     * llevan un rato sin hablar en el chat grupal. Construye el mensaje
     * (con su propio número de secuencia, igual que cualquier otro mensaje
     * grupal) y lo transmite. Corre en el hilo selector (ChatServer la
     * invoca al drenar la cola de avisos pendientes), pero igual toma el
     * candado: es el mismo estado compartido que tocan los workers.
     */
    void broadcastInactivityNotice(List<String> idleUsernames) {
        if (idleUsernames.isEmpty()) return;
        String names = String.join(", ", idleUsernames);
        String text = idleUsernames.size() == 1
                ? "💤 " + names + " lleva un rato sin escribir en el grupo."
                : "💤 " + names + " llevan un rato sin escribir en el grupo.";
        sessionsLock.lock();
        try {
            Map<String, Object> out = Messages.groupMessage("sistema", text, now(), ++sequence);
            broadcastAll(out);
        } finally {
            sessionsLock.unlock();
        }
    }

    private void handlePrivateMessage(ClientSession session, Map<String, Object> msg) {
        String to = stringField(msg, "to");
        String text = stringField(msg, "text");
        if (!isValidText(text)) {
            session.enqueue(Messages.error("El mensaje no puede estar vacío ni superar 8 KB."));
            return;
        }
        sessionsLock.lock();
        try {
            ClientSession recipient = to == null ? null : sessions.get(to);
            if (recipient == null) {
                session.enqueue(Messages.error("El usuario '" + to + "' no está conectado."));
                return;
            }
            Map<String, Object> out = Messages.privateMessage(
                    session.username(), to, text.trim(), now(), ++sequence);
            session.enqueue(out);
            if (recipient != session) {
                recipient.enqueue(out);
            }
        } finally {
            sessionsLock.unlock();
        }
    }

    /** Siempre llamado con sessionsLock ya tomado (ReentrantLock permite
     * la reentrada del mismo hilo, pero por diseño todas las llamadas a
     * este método ya vienen de dentro de una sección crítica). */
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
