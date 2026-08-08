package chat.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Extrae líneas delimitadas por '\n' directamente de un InputStream de socket.
 * TCP es un flujo de bytes: un JSON puede llegar fragmentado en varias lecturas,
 * o varios JSON pueden llegar juntos en una sola lectura. Esta clase mantiene un
 * búfer interno entre llamadas para reconstruir cada línea completa y rechaza
 * líneas que superen maxBytes sin bloquear memoria de forma ilimitada.
 */
public final class LineReader {
    private final InputStream in;
    private final int maxBytes;
    private final byte[] chunk = new byte[8192];
    private int chunkStart = 0;
    private int chunkEnd = 0;
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();

    public LineReader(InputStream in, int maxBytes) {
        this.in = in;
        this.maxBytes = maxBytes;
    }

    /** Devuelve la siguiente línea (sin '\n' ni '\r' final) o null si el flujo terminó. */
    public String readLine() throws IOException {
        lineBuffer.reset();
        while (true) {
            if (chunkStart >= chunkEnd) {
                chunkEnd = in.read(chunk);
                chunkStart = 0;
                if (chunkEnd == -1) {
                    return null;
                }
            }
            for (int i = chunkStart; i < chunkEnd; i++) {
                if (chunk[i] == '\n') {
                    if (lineBuffer.size() + (i - chunkStart) > maxBytes) {
                        chunkStart = i + 1;
                        throw new LineTooLongException("Línea mayor de " + maxBytes + " bytes.");
                    }
                    lineBuffer.write(chunk, chunkStart, i - chunkStart);
                    chunkStart = i + 1;
                    return finishLine();
                }
            }
            int available = chunkEnd - chunkStart;
            if (lineBuffer.size() + available > maxBytes) {
                chunkStart = chunkEnd;
                throw new LineTooLongException("Línea mayor de " + maxBytes + " bytes.");
            }
            lineBuffer.write(chunk, chunkStart, available);
            chunkStart = chunkEnd;
        }
    }

    private String finishLine() {
        byte[] bytes = lineBuffer.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
            len--;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }
}
