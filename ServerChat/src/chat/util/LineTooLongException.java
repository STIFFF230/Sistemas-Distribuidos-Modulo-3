package chat.util;

import java.io.IOException;

public final class LineTooLongException extends IOException {
    public LineTooLongException(String message) {
        super(message);
    }
}
