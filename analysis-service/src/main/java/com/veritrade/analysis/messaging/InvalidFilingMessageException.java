package com.veritrade.analysis.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/**
 * A {@code filing.submitted} message that cannot be read or validated. It is never retried: the
 * retry policy skips it and the container rejects it without requeue, so it goes straight to the
 * dead-letter queue.
 */
public class InvalidFilingMessageException extends AmqpRejectAndDontRequeueException {

    public InvalidFilingMessageException(String message) {
        super(message);
    }

    public InvalidFilingMessageException(String message, Throwable cause) {
        super(message, cause);
    }

    /** True when {@code throwable} is, or was caused by, an invalid message. */
    public static boolean isCauseOf(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof InvalidFilingMessageException) {
                return true;
            }
        }
        return false;
    }
}
