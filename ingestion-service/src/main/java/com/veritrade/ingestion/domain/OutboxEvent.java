package com.veritrade.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An event waiting to be published. The id is the event id of the envelope stored in the payload.
 * {@code attempts} counts only failures of this row itself; a row that reaches the maximum is parked
 * ({@code parked_at} set) and is no longer sent.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    /** Length of the {@code last_error} column (V2 migration). */
    public static final int LAST_ERROR_LENGTH = 1000;

    @Id
    private UUID id;

    @Column(name = "routing_key", nullable = false)
    private String routingKey;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = LAST_ERROR_LENGTH)
    private String lastError;

    @Column(name = "parked_at")
    private Instant parkedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID id, String routingKey, String correlationId, String payload, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.routingKey = Objects.requireNonNull(routingKey, "routingKey");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public UUID id() {
        return id;
    }

    public String routingKey() {
        return routingKey;
    }

    public String correlationId() {
        return correlationId;
    }

    public String payload() {
        return payload;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public int attempts() {
        return attempts;
    }
}
