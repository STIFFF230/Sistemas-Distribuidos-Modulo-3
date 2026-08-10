package chat.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codec JSON mínimo, sin dependencias externas (el proyecto se compila solo con el JDK).
 * Soporta objetos, arrays, strings, números, booleanos y null, que es todo lo que
 * necesita el protocolo del chat. Reemplaza al enfoque basado en expresiones regulares
 * de Cajeroreferencia porque ese no soportaba arrays (USER_LIST) ni números (sequence).
 */
public final class Json {
    private Json() {
    }

    // ---------- Escritura ----------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(s, sb);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            if (n instanceof Double || n instanceof Float) {
                sb.append(n.toString());
            } else {
                sb.append(n.longValue());
            }
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(String.valueOf(entry.getKey()), sb);
                sb.append(':');
                writeValue(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                writeValue(item, sb);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Tipo no serializable: " + value.getClass());
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------- Lectura ----------

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonParseException("Contenido inesperado después del JSON.");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonParseException("Se esperaba un objeto JSON.");
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            if (pos >= text.length()) {
                throw new JsonParseException("Fin de entrada inesperado.");
            }
            return text.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void expect(char c) {
            if (next() != c) {
                throw new JsonParseException("Se esperaba '" + c + "' en la posición " + (pos - 1) + ".");
            }
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> parseObjectValue();
                case '[' -> parseArrayValue();
                case '"' -> parseStringValue();
                case 't', 'f' -> parseBooleanValue();
                case 'n' -> parseNullValue();
                default -> parseNumberValue();
            };
        }

        Map<String, Object> parseObjectValue() {
            Map<String, Object> result = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseStringValue();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw new JsonParseException("Se esperaba ',' o '}'.");
            }
            return result;
        }

        List<Object> parseArrayValue() {
            List<Object> result = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw new JsonParseException("Se esperaba ',' o ']'.");
                skipWhitespace();
            }
            return result;
        }

        String parseStringValue() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') break;
                if (c == '\\') {
                    char escape = next();
                    switch (escape) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw new JsonParseException("Secuencia \\u incompleta.");
                            }
                            String hex = text.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new JsonParseException("Escape inválido: \\" + escape);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Boolean parseBooleanValue() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new JsonParseException("Literal booleano inválido en la posición " + pos + ".");
        }

        Object parseNullValue() {
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new JsonParseException("Literal inválido en la posición " + pos + ".");
        }

        Number parseNumberValue() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            boolean isDecimal = false;
            if (pos < text.length() && text.charAt(pos) == '.') {
                isDecimal = true;
                pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                isDecimal = true;
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            }
            String number = text.substring(start, pos);
            if (number.isEmpty() || "-".equals(number)) {
                throw new JsonParseException("Número inválido en la posición " + start + ".");
            }
            return isDecimal ? (Number) Double.parseDouble(number) : (Number) Long.parseLong(number);
        }
    }
}
