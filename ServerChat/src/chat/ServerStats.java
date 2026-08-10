package chat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Único punto del servidor con estado compartido entre dos hilos de sistema
 * operativo distintos: el hilo selector (ChatServer) lo actualiza en cada
 * conexión/registro/mensaje, y el hilo StatsReporter lo lee cada cierto
 * tiempo para imprimir un resumen. Por eso -y solo por eso- necesita un
 * mecanismo de bloqueo explícito.
 *
 * Todo lo demás en este servidor (el mapa de sesiones y el contador de
 * secuencia en ServerDispatcher, los buffers de cada ClientSession) lo toca
 * un único hilo -el selector- y por diseño nunca se ejecuta en paralelo
 * consigo mismo, así que no necesita sincronización: no hay memoria
 * compartida entre hilos concurrentes ahí. Esta clase es la excepción y por
 * eso concentra, a propósito, el único candado de todo el proyecto.
 *
 * Se usa ReentrantLock (en vez de 'synchronized') para dejar el candado
 * explícito en el código (lock()/unlock() en try/finally), documentando la
 * región crítica exacta que protege.
 */
final class ServerStats {
    private final ReentrantLock lock = new ReentrantLock();
    private final Instant startedAt = Instant.now();

    private int currentConnections = 0;
    private int registeredUsers = 0;
    private long totalConnections = 0;
    private long groupMessages = 0;
    private long privateMessages = 0;

    void clientConnected() {
        lock.lock();
        try {
            currentConnections++;
            totalConnections++;
        } finally {
            lock.unlock();
        }
    }

    void clientDisconnected() {
        lock.lock();
        try {
            currentConnections--;
        } finally {
            lock.unlock();
        }
    }

    void userRegistered() {
        lock.lock();
        try {
            registeredUsers++;
        } finally {
            lock.unlock();
        }
    }

    void userUnregistered() {
        lock.lock();
        try {
            registeredUsers = Math.max(0, registeredUsers - 1);
        } finally {
            lock.unlock();
        }
    }

    void groupMessageSent() {
        lock.lock();
        try {
            groupMessages++;
        } finally {
            lock.unlock();
        }
    }

    void privateMessageSent() {
        lock.lock();
        try {
            privateMessages++;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Foto consistente de todos los contadores a la vez. Sin el candado, el
     * hilo reportero podría leer, por ejemplo, groupMessages ya incrementado
     * pero totalConnections todavía no (o al revés): una mezcla de estados
     * que nunca existió en ningún instante real. Con el candado, el snapshot
     * siempre corresponde a un instante único y coherente.
     */
    Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(currentConnections, registeredUsers, totalConnections,
                    groupMessages, privateMessages, Duration.between(startedAt, Instant.now()));
        } finally {
            lock.unlock();
        }
    }

    record Snapshot(int currentConnections, int registeredUsers, long totalConnections,
                     long groupMessages, long privateMessages, Duration uptime) {
    }
}
