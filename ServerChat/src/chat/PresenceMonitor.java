package chat;

import java.nio.channels.Selector;
import java.util.List;
import java.util.Queue;

/**
 * Hilo independiente del selector. Cada CHECK_INTERVAL_MS revisa, a través
 * de ChatActivityTracker, qué usuarios llevan callados en el chat grupal, y
 * si encuentra alguno, publica un aviso DENTRO del propio chat grupal.
 *
 * Nunca toca sockets ni el mapa de sesiones directamente -eso sigue siendo
 * exclusivo del hilo selector-: solo encola qué usuarios avisar y despierta
 * al selector con wakeup(), igual que cualquier canal listo para leer o
 * escribir. Es el selector quien arma y envía el mensaje real.
 */
final class PresenceMonitor implements Runnable {
    private static final long CHECK_INTERVAL_MS = 20_000;
    private static final long IDLE_THRESHOLD_MS = 20_000;

    private final ChatActivityTracker tracker;
    private final Queue<List<String>> pendingNotices;
    private final Selector selector;

    PresenceMonitor(ChatActivityTracker tracker, Queue<List<String>> pendingNotices, Selector selector) {
        this.tracker = tracker;
        this.pendingNotices = pendingNotices;
        this.selector = selector;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Thread.sleep(CHECK_INTERVAL_MS);
                List<String> idle = tracker.pollIdleUsernames(IDLE_THRESHOLD_MS);
                if (!idle.isEmpty()) {
                    pendingNotices.offer(idle);
                    selector.wakeup();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
