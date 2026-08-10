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
import java.util.Set;

/**
 * Servidor reactor de un solo hilo: un Selector multiplexa accept/read/write
 * de TODOS los clientes sobre canales NO bloqueantes (SocketChannel en modo
 * no-blocking). No hay un hilo por cliente ni llamadas bloqueantes a
 * accept()/read()/write() -- selector.select() es la única llamada que puede
 * esperar, y espera por *cualquier* canal que tenga algo listo, no por uno
 * en particular. Corresponde al modelo "máquina de estado finito" descrito
 * en la lectura complementaria 3 (sección 3.4.1, figura 3-4): paralelismo
 * lógico entre conexiones + llamadas de E/S sin bloqueo, en vez del modelo
 * "hilos" (paralelismo real vía hilos del SO + llamadas de bloqueo) que
 * usaba la versión anterior de este servidor.
 */
public final class ChatServer {
    private static final int READ_BUFFER_SIZE = 8192;

    private final ServerConfig config;

    public ChatServer(ServerConfig config) {
        this.config = config;
    }

    public void start() {
        ServerStats stats = new ServerStats();
        Thread statsThread = new Thread(new StatsReporter(stats), "stats-reporter");
        statsThread.setDaemon(true);
        statsThread.start();

        ServerDispatcher dispatcher = new ServerDispatcher(stats);
        ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);

        try (Selector selector = Selector.open();
             ServerSocketChannel serverChannel = ServerSocketChannel.open()) {

            serverChannel.bind(new InetSocketAddress(config.port()));
            serverChannel.configureBlocking(false);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            log("Escuchando en el puerto " + config.port() + " (todas las interfaces, sockets no bloqueantes).");

            while (true) {
                selector.select(); // única llamada que puede bloquear: espera CUALQUIER canal listo

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
                            read(key, dispatcher, readBuffer);
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

    private void accept(ServerSocketChannel serverChannel, Selector selector, ServerDispatcher dispatcher)
            throws IOException {
        SocketChannel client = serverChannel.accept();
        if (client == null) return; // wakeup espurio: nada que aceptar en realidad
        client.configureBlocking(false);
        client.setOption(StandardSocketOptions.TCP_NODELAY, true);

        ClientSession session = new ClientSession(client, config.maxMessageBytes());
        SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ);
        clientKey.attach(session);
        session.attachKey(clientKey);

        dispatcher.onConnected(session);
        log("Cliente conectado: " + session.remoteAddress());
    }

    private void read(SelectionKey key, ServerDispatcher dispatcher, ByteBuffer buffer) throws IOException {
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
                    dispatcher.onLine(session, line);
                }
            }
        } catch (LineTooLongException e) {
            dispatcher.onMalformed(session, e.getMessage());
            disconnect(key, dispatcher);
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
