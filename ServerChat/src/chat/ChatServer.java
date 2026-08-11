package chat;

import chat.util.LineTooLongException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Servidor reactor + WorkerPool.
 *
 * El SOCKET sigue siendo territorio exclusivo de un único hilo: solo el
 * hilo selector llama a accept()/read()/write() o toca una SelectionKey,
 * multiplexando TODOS los canales (no bloqueantes) con un único
 * Selector.select() -- esa parte no cambia frente al modelo de un solo
 * hilo, y sigue siendo la respuesta directa a "sockets no bloqueantes".
 *
 * Lo que sí cambia es qué se hace con una línea ya completa: en vez de
 * procesarla ahí mismo, el hilo selector la dobla en el buzón (mailbox)
 * del cliente y la agenda en una BlockingQueue compartida; un WorkerPool
 * de WORKER_COUNT hilos fijos la recoge y ejecuta la lógica de negocio
 * (ServerDispatcher) EN PARALELO entre clientes distintos. Es el modelo
 * "hilos" de la lectura complementaria 3 (paralelismo real de hilos del
 * SO) combinado con E/S sin bloqueo (Selector) -- el mismo patrón
 * Selector + BlockingQueue + WorkerPool usado como referencia en el
 * laboratorio.
 *
 * Para que dos mensajes del MISMO cliente nunca se procesen a la vez (y
 * así nunca se desordenen), cada ClientSession tiene una compuerta de un
 * solo permiso (scheduled, un AtomicBoolean con compareAndSet): mientras
 * haya una tarea de ese cliente en la cola o corriendo en un worker, no se
 * agenda una segunda. Entre clientes distintos sí hay paralelismo real.
 */
public final class ChatServer {
    private static final int READ_BUFFER_SIZE = 8192;
    private static final int WORKER_COUNT = 4;

    private final ServerConfig config;

    public ChatServer(ServerConfig config) {
        this.config = config;
    }

    public void start() {
        ChatActivityTracker activityTracker = new ChatActivityTracker();
        ServerDispatcher dispatcher = new ServerDispatcher(activityTracker);
        BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
        Queue<List<String>> pendingNotices = new ConcurrentLinkedQueue<>();
        ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);

        try (Selector selector = Selector.open();
             ServerSocketChannel serverChannel = ServerSocketChannel.open()) {

            serverChannel.bind(new InetSocketAddress(config.port()));
            serverChannel.configureBlocking(false);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            startWorkerPool(taskQueue);

            Thread presenceThread = new Thread(
                    new PresenceMonitor(activityTracker, pendingNotices, selector), "presence-monitor");
            presenceThread.setDaemon(true);
            presenceThread.start();

            log("Escuchando en el puerto " + config.port()
                    + " (todas las interfaces, sockets no bloqueantes, " + WORKER_COUNT + " workers).");

            while (true) {
                selector.select(); // única llamada que puede bloquear: espera CUALQUIER canal listo, o un wakeup()

                List<String> notice;
                while ((notice = pendingNotices.poll()) != null) {
                    dispatcher.broadcastInactivityNotice(notice);
                }

                Set<SelectionKey> ready = selector.selectedKeys();
                Iterator<SelectionKey> it = ready.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) continue;
                    try {
                        if (key.isAcceptable()) {
                            accept(serverChannel, selector, dispatcher);
                        } else if (key.isReadable()) {
                            read(key, dispatcher, readBuffer, taskQueue);
                        } else if (key.isWritable()) {
                            write(key);
                        }
                    } catch (IOException | RuntimeException e) {
                        // Una falla en UN cliente no debe tumbar el selector ni a los
                        // demás clientes. Si la llave tiene una sesión adjunta (lectura,
                        // escritura), se cierra esa conexión; si no (falla dentro de
                        // accept(), cuya llave es la del propio ServerSocketChannel), solo
                        // se registra el error sin cerrar el socket de escucha.
                        if (key.attachment() instanceof ClientSession) {
                            disconnect(key, dispatcher);
                        } else {
                            log("Error de accept(): " + e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("No fue posible iniciar el servidor.", e);
        }
    }

    /** Arranca los hilos trabajadores fijos. Cada uno, en bucle infinito,
     * toma una tarea de la cola compartida (take() bloquea sin consumir
     * CPU mientras no haya nada que hacer) y la ejecuta. Un error dentro
     * de una tarea se registra y el worker sigue vivo para la siguiente. */
    private void startWorkerPool(BlockingQueue<Runnable> taskQueue) {
        for (int i = 1; i <= WORKER_COUNT; i++) {
            Thread worker = new Thread(() -> {
                while (true) {
                    try {
                        Runnable task = taskQueue.take();
                        try {
                            task.run();
                        } catch (RuntimeException e) {
                            log("Error procesando tarea en worker: " + e.getMessage());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "chat-worker-" + i);
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void accept(ServerSocketChannel serverChannel, Selector selector, ServerDispatcher dispatcher)
            throws IOException {
        SocketChannel client = serverChannel.accept();
        if (client == null) return; // wakeup espurio o de otra tarea: nada que aceptar en realidad
        client.configureBlocking(false);
        client.setOption(StandardSocketOptions.TCP_NODELAY, true);

        ClientSession session = new ClientSession(client, config.maxMessageBytes());
        SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ);
        clientKey.attach(session);
        session.attachKey(clientKey);

        dispatcher.onConnected(session);
        log("Cliente conectado: " + session.remoteAddress());
    }

    private void read(SelectionKey key, ServerDispatcher dispatcher, ByteBuffer buffer, BlockingQueue<Runnable> taskQueue)
            throws IOException {
        ClientSession session = (ClientSession) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();

        buffer.clear();
        int n = channel.read(buffer);
        if (n == -1) {
            disconnect(key, dispatcher);
            return;
        }
        if (n == 0) return; // nada que leer todavía (no debería pasar si OP_READ estaba listo, pero por si acaso)

        buffer.flip();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        try {
            for (String line : session.accumulator().append(data, data.length)) {
                if (!line.isBlank()) {
                    session.mailbox().offer(line);
                }
            }
            scheduleIfNeeded(session, dispatcher, taskQueue);
        } catch (LineTooLongException e) {
            dispatcher.onMalformed(session, e.getMessage());
            disconnect(key, dispatcher);
        }
    }

    /**
     * Agenda una tarea de procesamiento para este cliente SOLO si no hay
     * ya una en curso o esperando en la cola (compareAndSet sobre
     * scheduled). Así se garantiza que, aunque varios workers procesen
     * clientes distintos en paralelo, nunca haya dos workers tocando el
     * mismo cliente a la vez, y sus mensajes se procesan en el mismo
     * orden en que llegaron.
     */
    private void scheduleIfNeeded(ClientSession session, ServerDispatcher dispatcher, BlockingQueue<Runnable> taskQueue) {
        if (session.mailbox().isEmpty()) return;
        if (session.scheduled().compareAndSet(false, true)) {
            taskQueue.offer(() -> processMailbox(session, dispatcher, taskQueue));
        }
    }

    /** Corre en un hilo del WorkerPool: procesa todo lo que haya en el
     * buzón del cliente, un mensaje a la vez y en orden. */
    private void processMailbox(ClientSession session, ServerDispatcher dispatcher, BlockingQueue<Runnable> taskQueue) {
        try {
            String line;
            while ((line = session.mailbox().poll()) != null) {
                try {
                    dispatcher.onLine(session, line);
                } catch (RuntimeException e) {
                    log("Error procesando mensaje de " + session.remoteAddress() + ": " + e.getMessage());
                }
            }
        } finally {
            session.scheduled().set(false);
            // Recheck: pudo llegar un mensaje nuevo justo mientras se
            // soltaba el permiso. Si el buzón ya no está vacío, se vuelve
            // a agendar (posiblemente en otro worker).
            scheduleIfNeeded(session, dispatcher, taskQueue);
        }
    }

    private void write(SelectionKey key) throws IOException {
        ClientSession session = (ClientSession) key.attachment();
        session.flushPending();
    }

    private void disconnect(SelectionKey key, ServerDispatcher dispatcher) {
        ClientSession session = (ClientSession) key.attachment();
        if (session != null) {
            dispatcher.onDisconnected(session);
        }
        try {
            key.channel().close();
        } catch (IOException ignored) {
            // El canal ya puede estar cerrado o roto.
        }
        key.cancel();
    }

    private static void log(String message) {
        System.out.println("[ChatServer] " + message);
    }
}
