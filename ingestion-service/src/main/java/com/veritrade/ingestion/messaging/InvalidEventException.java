package com.veritrade.ingestion.messaging;

import org.springframework.amqp.support.converter.MessageConversionException;

/**
 * A message that can never be processed (unreadable, invalid or about an unknown filing).
 * It is not retried; the recoverer sends it straight to the dead-letter queue. A body that the
 * listener adapter already fails to convert ({@link MessageConversionException}) counts as unreadable too.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public static boolean isUnprocessable(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof InvalidEventException || current instanceof MessageConversionException) {
                return true;
            }
        }
        return false;
    }
}
