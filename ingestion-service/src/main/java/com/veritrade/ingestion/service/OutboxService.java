package com.veritrade.ingestion.service;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.ingestion.config.IngestionProperties;
import com.veritrade.ingestion.domain.OutboxEvent;
import com.veritrade.ingestion.repository.OutboxRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Stores events in the outbox table and tracks which of them reached the broker. */
@Service
public class OutboxService {

    private final OutboxRepository outbox;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final int batchSize;

    public OutboxService(OutboxRepository outbox, JsonMapper jsonMapper, Clock clock, IngestionProperties properties) {
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.batchSize = properties.outbox().batchSize();
    }

    /** Must run inside the transaction that changes the business data, so both commit or neither does. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(EventEnvelope<?> envelope) {
        String payload = jsonMapper.writeValueAsString(envelope);
        outbox.save(new OutboxEvent(envelope.eventId(), envelope.eventType().routingKey(),
                envelope.correlationId(), payload, envelope.occurredAt()));
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> nextBatch() {
        return outbox.findByPublishedAtIsNullOrderByCreatedAtAscIdAsc(Limit.of(batchSize));
    }

    @Transactional
    public void markPublished(UUID eventId) {
        outbox.markPublished(eventId, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
