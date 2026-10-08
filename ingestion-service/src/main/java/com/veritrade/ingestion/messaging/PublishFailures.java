package com.veritrade.ingestion.messaging;

import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.AmqpResourceNotAvailableException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.support.converter.MessageConversionException;

/**
 * Decides whether a failed send is the fault of the message itself, so it counts against the outbox row.
 *
 * <p>The rule: a failure counts only when the message is refused while it is being built or encoded, before
 * anything reaches the broker. That is an {@link IllegalArgumentException} anywhere in the cause chain (the
 * AMQP client's encoding checks, e.g. a short string such as {@code correlationId} over 255 UTF-8 bytes, or an
 * unsupported header value) or a {@link MessageConversionException}. Such a message fails the same way on every
 * attempt, whatever the state of the broker.
 *
 * <p>Everything else is transient and never counts, so it never parks a row and the strict order is kept:
 * a connection, I/O, timeout, authentication or channel-limit failure (even when an
 * {@code IllegalArgumentException} is among its causes, e.g. a broken broker address, because that would fail
 * every row), any other {@code AmqpException}, and every confirm outcome (a nack, a return as unroutable while
 * Analysis has not yet declared its queue, a missing confirm). Those are handled by the caller without
 * reaching this class.
 */
final class PublishFailures {

    private PublishFailures() {
    }

    static boolean isCausedByTheMessage(RuntimeException failure) {
        if (isConnectionFailure(failure)) {
            return false;
        }
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof IllegalArgumentException || current instanceof MessageConversionException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConnectionFailure(RuntimeException failure) {
        return failure instanceof AmqpConnectException
                || failure instanceof AmqpIOException
                || failure instanceof AmqpTimeoutException
                || failure instanceof AmqpAuthenticationException
                || failure instanceof AmqpResourceNotAvailableException;
    }
}
