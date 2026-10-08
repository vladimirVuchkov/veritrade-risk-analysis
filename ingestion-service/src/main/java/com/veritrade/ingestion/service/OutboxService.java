package com.veritrade.ingestion.service;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.ingestion.config.IngestionProperties;
import com.veritrade.ingestion.domain.OutboxEvent;
import com.veritrade.ingestion.repository.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores events in the outbox table and tracks which of them reached the broker, and which of them
 * failed so often on their own that they are parked.
 */
@Service
public class OutboxService {

    private final OutboxRepository outbox;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxService(final OutboxRepository outbox, final JsonMapper jsonMapper, final Clock clock, final IngestionProperties properties) {
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.batchSize = properties.outbox().batchSize();
        this.maxAttempts = properties.outbox().maxAttempts();
    }

    /** Must run inside the transaction that changes the business data, so both commit or neither does. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(final EventEnvelope<?> envelope) {
        final String payload = jsonMapper.writeValueAsString(envelope);
        outbox.save(new OutboxEvent(envelope.eventId(), envelope.eventType().routingKey(),
                envelope.correlationId(), payload, envelope.occurredAt()));
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> nextBatch() {
        return outbox.findByPublishedAtIsNullAndParkedAtIsNullOrderByCreatedAtAscIdAsc(Limit.of(batchSize));
    }

    @Transactional
    public void markPublished(final UUID eventId) {
        outbox.markPublished(eventId, now());
    }

    /**
     * Counts a failure of the row itself (never a broker outage) and parks the row once it has failed
     * {@code ingestion.outbox.max-attempts} times. Returns true when the row is parked now.
     */
    @Transactional
    public boolean recordFailedAttempt(final UUID eventId, final String error) {
        outbox.recordFailedAttempt(eventId, shorten(error));
        return outbox.parkIfExhausted(eventId, maxAttempts, now()) == 1;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String shorten(final String error) {
        return error.length() <= OutboxEvent.LAST_ERROR_LENGTH ? error : error.substring(0, OutboxEvent.LAST_ERROR_LENGTH);
    }
}
