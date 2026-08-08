package chat;

import chat.protocol.Json;
import chat.protocol.JsonParseException;
import chat.util.LineReader;
import chat.util.LineTooLongException;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/** Lectura bloqueante permanente del socket. Produce eventos, nunca escribe en el socket. */
final class ClientReader implements Runnable {
    private final ClientSession session;
    private final BlockingQueue<ClientEvent> events;
    private final ServerConfig config;

    ClientReader(ClientSession session, BlockingQueue<ClientEvent> events, ServerConfig config) {
        this.session = session;
        this.events = events;
        this.config = config;
    }

    @Override
    public void run() {
        try {
            LineReader lineReader = new LineReader(session.socket().getInputStream(), config.maxMessageBytes());
            String line;
            while ((line = lineReader.readLine()) != null) {
                if (!line.isBlank()) {
                    handleLine(line);
                }
            }
        } catch (LineTooLongException e) {
            events.offer(ClientEvent.malformed(session, "Mensaje mayor de " + config.maxMessageBytes() + " bytes."));
        } catch (IOException ignored) {
            // Conexión perdida o socket cerrado; se notifica abajo en el finally.
        } finally {
            events.offer(ClientEvent.disconnected(session));
        }
    }

    private void handleLine(String line) {
        try {
            Map<String, Object> message = Json.parseObject(line);
            if (!(message.get("type") instanceof String)) {
                events.offer(ClientEvent.malformed(session, "Falta el campo 'type'."));
                return;
            }
            events.offer(ClientEvent.message(session, message));
        } catch (JsonParseException e) {
            events.offer(ClientEvent.malformed(session, "JSON inválido: " + e.getMessage()));
        }
    }
}
