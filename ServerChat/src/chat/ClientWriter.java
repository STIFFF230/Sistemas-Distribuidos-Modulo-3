package chat;

import chat.protocol.Json;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/** Único escritor del socket: consume la cola FIFO de salida y envía cada mensaje en orden. */
final class ClientWriter implements Runnable {
    private final ClientSession session;
    private final BlockingQueue<ClientEvent> events;

    ClientWriter(ClientSession session, BlockingQueue<ClientEvent> events) {
        this.session = session;
        this.events = events;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Map<String, Object> message = session.outboundQueue().take();
                session.writer().write(Json.write(message));
                session.writer().newLine();
                session.writer().flush();
            }
        } catch (InterruptedException expected) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            events.offer(ClientEvent.disconnected(session));
        }
    }
}
