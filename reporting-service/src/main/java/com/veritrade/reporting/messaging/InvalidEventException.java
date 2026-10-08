package com.veritrade.reporting.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/** A message that cannot be read or validated. It is never retried and goes straight to the dead-letter queue. */
public class InvalidEventException extends AmqpRejectAndDontRequeueException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
