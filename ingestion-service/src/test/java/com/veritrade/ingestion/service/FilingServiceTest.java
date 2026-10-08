package com.veritrade.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.event.FilingSubmittedPayload;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.ingestion.domain.Filing;
import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.domain.FilingView;
import com.veritrade.ingestion.repository.FilingRepository;
import com.veritrade.ingestion.support.TestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

class FilingServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00.123456789Z");
    private static final Instant NOW_IN_MICROS = Instant.parse("2026-10-07T12:00:00.123456Z");

    private final FilingRepository filings = mock(FilingRepository.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final FilingService service = new FilingService(filings, outbox,
            new FilingValidator(TestProperties.defaults()), Clock.fixed(NOW, ZoneOffset.UTC), TestProperties.defaults());

    @Test
    void storesTheFilingAsSubmittedWithTimestampInMicroseconds() {
        when(filings.save(any(Filing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        final FilingView view = service.submit(new FilingSubmission(" Acme ", "10-K", "text"), "corr-1");

        assertThat(view.status()).isEqualTo(FilingStatus.SUBMITTED);
        assertThat(view.companyName()).isEqualTo("Acme");
        assertThat(view.submittedAt()).isEqualTo(NOW_IN_MICROS);
        assertThat(view.failureReason()).isNull();
    }

    @Test
    void enqueuesTheFilingSubmittedEventForTheSameFiling() {
        when(filings.save(any(Filing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        final FilingView view = service.submit(new FilingSubmission("Acme", "10-K", "text"), "corr-1");

        final EventEnvelope<?> envelope = capturedEnvelope();
        assertThat(envelope.eventId()).isEqualTo(EventIds.forFiling(view.id(), EventType.FILING_SUBMITTED));
        assertThat(envelope.eventType()).isEqualTo(EventType.FILING_SUBMITTED);
        assertThat(envelope.eventVersion()).isEqualTo(EventEnvelope.CURRENT_VERSION);
        assertThat(envelope.occurredAt()).isEqualTo(NOW_IN_MICROS);
        assertThat(envelope.correlationId()).isEqualTo("corr-1");
        assertThat(envelope.payload()).isEqualTo(
                new FilingSubmittedPayload(view.id(), "Acme", "10-K", "text", NOW_IN_MICROS));
    }

    @Test
    void storesNothingWhenTheSubmissionIsInvalid() {
        assertThatThrownBy(() -> service.submit(new FilingSubmission("Acme", "10-K", " "), "corr-1"))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(filings, outbox);
    }

    @Test
    void requiresACorrelationId() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.submit(new FilingSubmission("Acme", "10-K", "text"), null));
    }

    @Test
    void returnsAKnownFiling() {
        final UUID id = UUID.randomUUID();
        final FilingView view = new FilingView(id, "Acme", "10-K", FilingStatus.ANALYZING, NOW, null);
        when(filings.findViewById(id)).thenReturn(Optional.of(view));

        assertThat(service.get(id)).isEqualTo(view);
    }

    @Test
    void failsForAnUnknownFiling() {
        final UUID id = UUID.randomUUID();
        when(filings.findViewById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOfSatisfying(FilingNotFoundException.class, e -> assertThat(e.filingId()).isEqualTo(id))
                .hasMessageContaining(id.toString());
    }

    @Test
    void listsTheDefaultNumberWhenNoLimitIsGiven() {
        service.listRecent(null);

        verify(filings).findAllByOrderBySubmittedAtDescIdDesc(Limit.of(TestProperties.DEFAULT_LIMIT));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 20, 99, 100})
    void acceptsLimitsFromOneToTheMaximum(final int limit) {
        final List<FilingView> expected = List.of(new FilingView(UUID.randomUUID(), "Acme", "10-K", FilingStatus.SUBMITTED, NOW, null));
        when(filings.findAllByOrderBySubmittedAtDescIdDesc(Limit.of(limit))).thenReturn(expected);

        assertThat(service.listRecent(limit)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100, 101, 1000, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void rejectsLimitsOutsideTheRange(final int limit) {
        assertThatThrownBy(() -> service.listRecent(limit))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.errors()).containsExactly("limit must be between 1 and 100"));
        verifyNoInteractions(filings);
    }

    private EventEnvelope<?> capturedEnvelope() {
        final ArgumentCaptor<EventEnvelope<?>> captor = ArgumentCaptor.captor();
        verify(outbox).enqueue(captor.capture());
        return captor.getValue();
    }
}
