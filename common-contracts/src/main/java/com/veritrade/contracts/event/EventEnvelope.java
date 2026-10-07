package com.veritrade.contracts.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Common wrapper for every event on the bus. Consumers must ignore unknown fields (tolerant reader),
 * so a newer producer and an older consumer can run side by side.
 */
public record EventEnvelope<T>(
        UUID eventId,
        EventType eventType,
        int eventVersion,
        Instant occurredAt,
        String correlationId,
        T payload) {

    public static final int CURRENT_VERSION = 1;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be >= 1");
        }
    }

    public static <T> EventEnvelope<T> of(
            UUID eventId, EventType eventType, Instant occurredAt, String correlationId, T payload) {
        return new EventEnvelope<>(eventId, eventType, CURRENT_VERSION, occurredAt, correlationId, payload);
    }
}
