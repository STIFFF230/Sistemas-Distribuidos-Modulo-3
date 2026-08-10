package chat.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Extrae líneas delimitadas por '\n' a partir de bytes recibidos de un
 * SocketChannel NO bloqueante.
 *
 * Diferencia clave con la antigua LineReader (que llamaba a
 * InputStream.read(), una operación de bloqueo de sistema): esta clase nunca
 * bloquea ni pide más datos. Solo procesa los bytes que el hilo selector ya
 * leyó con SocketChannel.read() (no bloqueante) y devuelve las líneas que
 * pudo completar; lo que sobra queda en un búfer interno esperando la
 * siguiente llamada a append(), que puede ocurrir mucho después si el
 * cliente escribe lento o el mensaje llegó fragmentado en varios paquetes
 * TCP. Como TCP es un flujo de bytes sin límites de mensaje, un JSON puede
 * llegar partido en varias lecturas o varias líneas pueden llegar juntas en
 * una sola lectura; ambos casos se manejan aquí.
 */
public final class LineAccumulator {
    private final int maxBytes;
    private byte[] buf = new byte[256];
    private int len = 0;

    public LineAccumulator(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * Agrega los bytes ya leídos (data[0..count)) y devuelve, en orden, las
     * líneas completas que se pudieron formar (sin el '\n' ni el '\r' final).
     * Puede devolver una lista vacía (línea aún incompleta) o con varias
     * líneas (llegaron varias de un tirón).
     */
    public List<String> append(byte[] data, int count) throws LineTooLongException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte b = data[i];
            if (b == '\n') {
                lines.add(drainLine());
            } else {
                if (len >= maxBytes) {
                    len = 0; // descarta lo acumulado; la conexión se cerrará
                    throw new LineTooLongException("Línea mayor de " + maxBytes + " bytes.");
                }
                ensureCapacity(len + 1);
                buf[len++] = b;
            }
        }
        return lines;
    }

    private String drainLine() {
        int end = len;
        if (end > 0 && buf[end - 1] == '\r') {
            end--;
        }
        String line = new String(buf, 0, end, StandardCharsets.UTF_8);
        len = 0;
        return line;
    }

    private void ensureCapacity(int needed) {
        if (needed > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, needed));
        }
    }
}
