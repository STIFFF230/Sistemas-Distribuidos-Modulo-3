package chat;

/**
 * Hilo independiente del selector que imprime un resumen periódico del
 * servidor. Es el único consumidor externo de ServerStats, y por lo tanto
 * la razón de ser del candado en esa clase: dos hilos de SO (este y el
 * selector) acceden al mismo estado mutable de forma concurrente.
 */
final class StatsReporter implements Runnable {
    private static final long INTERVAL_MS = 30_000;

    private final ServerStats stats;

    StatsReporter(ServerStats stats) {
        this.stats = stats;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Thread.sleep(INTERVAL_MS);
                ServerStats.Snapshot s = stats.snapshot();
                System.out.printf(
                        "[Stats] conexiones=%d usuarios=%d totalConexiones=%d msgGrupales=%d msgPrivados=%d uptime=%ds%n",
                        s.currentConnections(), s.registeredUsers(), s.totalConnections(),
                        s.groupMessages(), s.privateMessages(), s.uptime().toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
