package com.veritrade.analysis.messaging;

import com.veritrade.contracts.event.EventEnvelope;

/** The broker did not confirm an event: negative confirm, unroutable return, timeout or interruption. */
public class EventPublishException extends RuntimeException {

    public EventPublishException(EventEnvelope<?> event, String problem) {
        super(describe(event, problem));
    }

    public EventPublishException(EventEnvelope<?> event, String problem, Throwable cause) {
        super(describe(event, problem), cause);
    }

    private static String describe(EventEnvelope<?> event, String problem) {
        return "Could not publish " + event.eventType() + " event " + event.eventId() + ": " + problem;
    }
}
