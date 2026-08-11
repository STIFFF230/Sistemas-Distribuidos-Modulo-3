package chat;

import chat.protocol.Json;
import chat.util.LineAccumulator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Estado de una conexión sobre un SocketChannel NO bloqueante.
 *
 * El SOCKET en sí (channel, key) lo sigue tocando exclusivamente el hilo
 * selector, igual que siempre. Lo que cambia frente a la versión de un
 * solo hilo es que el PROCESAMIENTO de los mensajes de este cliente ahora
 * lo hace un hilo del WorkerPool -- nunca dos a la vez para el mismo
 * cliente (scheduled es una compuerta de un solo permiso vía CAS), pero sí
 * en paralelo con los workers que atienden a otros clientes. Por eso
 * mailbox, scheduled, pendingWrites y closed son estructuras seguras para
 * varios hilos (Concurrent*, Atomic*).
 */
final class ClientSession {
    private final SocketChannel channel;
    private final LineAccumulator accumulator;
    private final Queue<ByteBuffer> pendingWrites = new ConcurrentLinkedQueue<>();
    private final Queue<String> mailbox = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final String remoteAddress;

    private volatile SelectionKey key;
    private volatile String username;

    ClientSession(SocketChannel channel, int maxLineBytes) throws IOException {
        this.channel = channel;
        this.accumulator = new LineAccumulator(maxLineBytes);
        this.remoteAddress = String.valueOf(channel.getRemoteAddress());
    }

    void attachKey(SelectionKey key) {
        this.key = key;
    }

    LineAccumulator accumulator() {
        return accumulator;
    }

    /** Buzón de líneas completas aún no procesadas. Solo el hilo selector
     * escribe aquí (en read()); los workers lo drenan. */
    Queue<String> mailbox() {
        return mailbox;
    }

    /** Compuerta de un solo permiso: como máximo un worker procesa el
     * buzón de este cliente a la vez. Garantiza "serial por cliente,
     * paralelo entre clientes" sin usar un lock para esto en particular. */
    AtomicBoolean scheduled() {
        return scheduled;
    }

    /**
     * Serializa el mensaje y lo encola para envío. Puede llamarse desde
     * cualquier hilo del WorkerPool (varios workers pueden encolarle algo
     * a la misma sesión, p. ej. un broadcast) o desde el propio selector.
     * Pide al selector que avise (OP_WRITE) en cuanto el socket pueda
     * aceptar más bytes, y lo despierta con wakeup() por si estaba
     * bloqueado en select() esperando actividad de otros sockets.
     */
    void enqueue(Map<String, Object> message) {
        if (closed.get()) return;
        byte[] bytes = (Json.write(message) + "\n").getBytes(StandardCharsets.UTF_8);
        pendingWrites.offer(ByteBuffer.wrap(bytes));
        SelectionKey k = key;
        if (k != null && k.isValid()) {
            k.interestOps(k.interestOps() | SelectionKey.OP_WRITE);
            k.selector().wakeup();
        }
    }

    /**
     * Llamado SOLO por el hilo selector cuando el canal señala OP_WRITE.
     * write() en un canal no bloqueante puede escribir menos bytes de los
     * pedidos -- por eso hay que reintentar por partes en llamadas
     * sucesivas. Al final se re-revisa la cola antes de apagar OP_WRITE:
     * un worker pudo haber encolado un mensaje nuevo justo cuando este
     * método ya había visto la cola vacía.
     */
    void flushPending() throws IOException {
        ByteBuffer buf;
        while ((buf = pendingWrites.peek()) != null) {
            channel.write(buf);
            if (buf.hasRemaining()) {
                return; // el SO todavía no puede aceptar más bytes ahora mismo
            }
            pendingWrites.poll();
        }
        SelectionKey k = key;
        if (k != null && k.isValid()) {
            k.interestOps(SelectionKey.OP_READ);
            if (!pendingWrites.isEmpty()) {
                k.interestOps(k.interestOps() | SelectionKey.OP_WRITE);
            }
        }
    }

    void closeChannel() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // El canal ya puede estar cerrado o roto.
        }
        SelectionKey k = key;
        if (k != null) {
            k.cancel();
            k.selector().wakeup();
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

    /** Idempotente y atómico (CAS): devuelve true solo la primera vez que
     * se invoca, incluso si dos hilos lo llaman al mismo tiempo -- por
     * ejemplo, el selector detecta un socket roto justo cuando un worker
     * está procesando un DISCONNECT del mismo cliente. */
    boolean markClosed() {
        return closed.compareAndSet(false, true);
    }

    String remoteAddress() {
        return remoteAddress;
    }
}
