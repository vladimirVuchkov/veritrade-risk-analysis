package com.veritrade.ingestion.messaging;

import org.springframework.amqp.support.converter.MessageConversionException;

/**
 * A message that can never be processed (unreadable, invalid or about an unknown filing).
 * It is not retried; the recoverer sends it straight to the dead-letter queue. A body that the
 * listener adapter already fails to convert ({@link MessageConversionException}) counts as unreadable too.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(final String message) {
        super(message);
    }

    public InvalidEventException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public static boolean isUnprocessable(final Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof InvalidEventException || current instanceof MessageConversionException) {
                return true;
            }
        }
        return false;
    }
}
