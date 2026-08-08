package chat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Estado de una conexión: socket exclusivo del cliente, su cola de salida FIFO
 * y el nombre de usuario una vez registrado. Solo el ClientWriter escribe en el
 * socket (consumiendo outboundQueue) y solo el ClientReader lo lee.
 */
final class ClientSession {
    private final Socket socket;
    private final BufferedWriter writer;
    private final BlockingQueue<Map<String, Object>> outbound = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile String username;
    private volatile Thread writerThread;

    ClientSession(Socket socket, BufferedWriter writer) {
        this.socket = socket;
        this.writer = writer;
    }

    void attachWriterThread(Thread writerThread) {
        this.writerThread = writerThread;
    }

    Socket socket() {
        return socket;
    }

    BufferedWriter writer() {
        return writer;
    }

    BlockingQueue<Map<String, Object>> outboundQueue() {
        return outbound;
    }

    void enqueue(Map<String, Object> message) {
        if (!closed.get()) {
            outbound.offer(message);
        }
    }

    String username() {
        return username;
    }

    void setUsername(String username) {
        this.username = username;
    }

    boolean isRegistered() {
        return username != null;
    }

    /** CAS idempotente: devuelve true solo la primera vez que se invoca. */
    boolean markClosed() {
        return closed.compareAndSet(false, true);
    }

    void closeAndInterruptWriter() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // El socket ya puede estar cerrado o roto.
        }
        if (writerThread != null) {
            writerThread.interrupt();
        }
    }

    String remoteAddress() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }
}
