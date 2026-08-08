package chat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Hilo aceptador: crea un socket exclusivo por cliente y arranca su
 * ClientReader/ClientWriter. Todo el enrutamiento ocurre en ServerDispatcher,
 * que corre en su propio hilo consumiendo la cola global de eventos.
 */
public final class ChatServer {
    private final ServerConfig config;
    private final BlockingQueue<ClientEvent> events = new LinkedBlockingQueue<>();

    public ChatServer(ServerConfig config) {
        this.config = config;
    }

    public void start() {
        Thread dispatcherThread = new Thread(new ServerDispatcher(events), "dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();

        try (ServerSocket serverSocket = new ServerSocket(config.port())) {
            log("Escuchando en el puerto " + config.port() + " (todas las interfaces).");
            while (true) {
                Socket socket = serverSocket.accept();
                acceptClient(socket);
            }
        } catch (IOException e) {
            throw new IllegalStateException("No fue posible iniciar el servidor.", e);
        }
    }

    private void acceptClient(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            ClientSession session = new ClientSession(socket, writer);

            Thread writerThread = new Thread(
                    new ClientWriter(session, events), "writer-" + socket.getRemoteSocketAddress());
            Thread readerThread = new Thread(
                    new ClientReader(session, events, config), "reader-" + socket.getRemoteSocketAddress());
            session.attachWriterThread(writerThread);
            writerThread.setDaemon(true);
            readerThread.setDaemon(true);

            log("Cliente conectado: " + socket.getRemoteSocketAddress());
            writerThread.start();
            readerThread.start();
        } catch (IOException e) {
            log("Error aceptando cliente: " + e.getMessage());
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nada más que hacer.
            }
        }
    }

    private static void log(String message) {
        System.out.println("[ChatServer] " + message);
    }
}
