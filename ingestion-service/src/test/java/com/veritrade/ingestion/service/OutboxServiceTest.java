package com.veritrade.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.event.FilingSubmittedPayload;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.ingestion.domain.OutboxEvent;
import com.veritrade.ingestion.repository.OutboxRepository;
import com.veritrade.ingestion.support.Contracts;
import com.veritrade.ingestion.support.TestProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:01.987654321Z");
    private static final Instant MICROS = Instant.parse("2026-10-07T12:00:01.987654Z");
    private static final int BATCH_SIZE = 7;

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final OutboxService service = new OutboxService(repository, jsonMapper, Clock.fixed(NOW, ZoneOffset.UTC),
            TestProperties.withOutbox(BATCH_SIZE, Duration.ofSeconds(1)));

    @Test
    void storesTheEnvelopeAsJsonUnderItsEventId() {
        final EventEnvelope<FilingSubmittedPayload> envelope = submittedEnvelope();

        service.enqueue(envelope);

        final OutboxEvent row = savedRow();
        assertThat(row.id()).isEqualTo(envelope.eventId());
        assertThat(row.routingKey()).isEqualTo("filing.submitted");
        assertThat(row.correlationId()).isEqualTo("corr-7");
        assertThat(row.createdAt()).isEqualTo(envelope.occurredAt());
        assertThat(row.publishedAt()).isNull();
    }

    @Test
    void storedPayloadIsValidAgainstTheContractSchema() {
        service.enqueue(submittedEnvelope());

        final String payload = savedRow().payload();
        assertThat(Contracts.validate(EventType.FILING_SUBMITTED, payload)).isEmpty();
        final JsonNode json = jsonMapper.readTree(payload);
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-10-07T12:00:00.123456Z");
        assertThat(json.get("payload").get("content").asString()).isEqualTo("Risk factors: pending litigation.");
    }

    @Test
    void readsTheNextBatchWithTheConfiguredSize() {
        final List<OutboxEvent> rows = List.of(new OutboxEvent(UUID.randomUUID(), "filing.submitted", "c", "{}", NOW));
        when(repository.findByPublishedAtIsNullAndParkedAtIsNullOrderByCreatedAtAscIdAsc(Limit.of(BATCH_SIZE))).thenReturn(rows);

        assertThat(service.nextBatch()).isEqualTo(rows);
    }

    @Test
    void marksPublishedWithTheCurrentTimeInMicroseconds() {
        final UUID id = UUID.randomUUID();

        service.markPublished(id);

        verify(repository).markPublished(id, MICROS);
    }

    @Test
    void countsAFailedAttemptAndParksOnlyWhenTheMaximumIsReached() {
        final UUID id = UUID.randomUUID();
        when(repository.parkIfExhausted(id, TestProperties.MAX_ATTEMPTS, MICROS)).thenReturn(0, 1);

        assertThat(service.recordFailedAttempt(id, "IllegalArgumentException: Short string too long")).isFalse();
        assertThat(service.recordFailedAttempt(id, "IllegalArgumentException: Short string too long")).isTrue();

        verify(repository, times(2)).recordFailedAttempt(id, "IllegalArgumentException: Short string too long");
        assertThat(service.maxAttempts()).isEqualTo(TestProperties.MAX_ATTEMPTS);
    }

    @Test
    void cutsTheErrorToTheColumnLength() {
        final UUID id = UUID.randomUUID();

        service.recordFailedAttempt(id, "e".repeat(OutboxEvent.LAST_ERROR_LENGTH + 1));

        verify(repository).recordFailedAttempt(id, "e".repeat(OutboxEvent.LAST_ERROR_LENGTH));
    }

    private OutboxEvent savedRow() {
        final ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static EventEnvelope<FilingSubmittedPayload> submittedEnvelope() {
        final UUID filingId = UUID.randomUUID();
        final Instant at = Instant.parse("2026-10-07T12:00:00.123456Z");
        return EventEnvelope.of(EventIds.forFiling(filingId, EventType.FILING_SUBMITTED), EventType.FILING_SUBMITTED,
                at, "corr-7", new FilingSubmittedPayload(filingId, "Acme", "10-K", "Risk factors: pending litigation.", at));
    }
}
