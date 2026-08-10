package chat;

import chat.protocol.Json;
import chat.util.LineAccumulator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Estado de una conexión sobre un SocketChannel NO bloqueante.
 *
 * En la versión anterior (sockets bloqueantes) cada cliente tenía dos hilos
 * propios (ClientReader/ClientWriter) y por eso el estado compartido entre
 * ellos (outboundQueue, closed) necesitaba estructuras thread-safe
 * (BlockingQueue, AtomicBoolean, campos volatile). Aquí no hay hilos por
 * cliente: el único hilo selector de ChatServer es el que lee, escribe y
 * toca esta instancia, siempre uno a la vez y nunca en paralelo consigo
 * mismo, así que los campos son variables normales sin sincronización.
 */
final class ClientSession {
    private final SocketChannel channel;
    private final LineAccumulator accumulator;
    private final Deque<ByteBuffer> pendingWrites = new ArrayDeque<>();
    private final String remoteAddress;

    private SelectionKey key;
    private String username;
    private boolean closed;

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

    /** Serializa el mensaje y lo encola para envío; pide al selector que
     * avise (OP_WRITE) en cuanto el socket pueda aceptar más bytes. */
    void enqueue(Map<String, Object> message) {
        if (closed) return;
        byte[] bytes = (Json.write(message) + "\n").getBytes(StandardCharsets.UTF_8);
        pendingWrites.addLast(ByteBuffer.wrap(bytes));
        if (key != null && key.isValid()) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
    }

    /**
     * Llamado por ChatServer cuando el canal señala OP_WRITE. write() en un
     * canal no bloqueante puede escribir menos bytes de los pedidos (el
     * búfer de envío del SO está lleno) -- por eso hay que reintentar por
     * partes en llamadas sucesivas, nunca asumir que se escribió todo de
     * una vez como se podía asumir con BufferedWriter.write() bloqueante.
     */
    void flushPending() throws IOException {
        while (!pendingWrites.isEmpty()) {
            ByteBuffer buf = pendingWrites.peekFirst();
            channel.write(buf);
            if (buf.hasRemaining()) {
                return; // el SO todavía no puede aceptar más bytes ahora mismo
            }
            pendingWrites.pollFirst();
        }
        if (key != null && key.isValid()) {
            key.interestOps(SelectionKey.OP_READ); // nada pendiente: deja de escuchar OP_WRITE
        }
    }

    void closeChannel() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // El canal ya puede estar cerrado o roto.
        }
        if (key != null) key.cancel();
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

    /** Idempotente: devuelve true solo la primera vez que se invoca. */
    boolean markClosed() {
        if (closed) return false;
        closed = true;
        return true;
    }

    String remoteAddress() {
        return remoteAddress;
    }
}
