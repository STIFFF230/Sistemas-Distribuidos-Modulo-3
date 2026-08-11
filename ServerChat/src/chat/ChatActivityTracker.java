package chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Registra la última vez que cada usuario habló en el CHAT GRUPAL.
 *
 * Es estado compartido entre varios hilos de sistema operativo reales al
 * mismo tiempo:
 *   - cualquier hilo del WorkerPool (ServerDispatcher.handleGroupMessage,
 *     corriendo en paralelo para clientes distintos) llama a
 *     {@link #recordActivity} cada vez que un usuario habla en el grupo, y
 *     a {@link #remove} cuando se desconecta;
 *   - el hilo {@link PresenceMonitor} llama a {@link #pollIdleUsernames}
 *     cada cierto tiempo para detectar quién lleva callado en el grupo.
 *
 * A diferencia de un simple contador de estadísticas, el dato protegido
 * aquí ES el chat grupal mismo: quién ha hablado y cuándo.
 */
final class ChatActivityTracker {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Long> lastSpokeAtMillis = new HashMap<>();

    /** Llamado desde un hilo del WorkerPool cuando un usuario habla en el grupo. */
    void recordActivity(String username) {
        lock.lock();
        try {
            lastSpokeAtMillis.put(username, System.currentTimeMillis());
        } finally {
            lock.unlock();
        }
    }

    /** Llamado desde un hilo del WorkerPool (o el selector) cuando un usuario se desconecta. */
    void remove(String username) {
        lock.lock();
        try {
            lastSpokeAtMillis.remove(username);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Llamado desde el hilo PresenceMonitor: devuelve los usuarios que
     * llevan al menos {@code thresholdMillis} sin hablar en el chat
     * grupal, y de paso "reinicia" su reloj para no repetir el mismo
     * aviso en cada chequeo posterior. Lectura + reinicio ocurren dentro
     * de la misma región crítica, de forma atómica.
     */
    List<String> pollIdleUsernames(long thresholdMillis) {
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            List<String> idle = new ArrayList<>();
            for (Map.Entry<String, Long> entry : lastSpokeAtMillis.entrySet()) {
                if (now - entry.getValue() >= thresholdMillis) {
                    idle.add(entry.getKey());
                }
            }
            for (String username : idle) {
                lastSpokeAtMillis.put(username, now);
            }
            return idle;
        } finally {
            lock.unlock();
        }
    }
}
