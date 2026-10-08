package com.veritrade.ingestion.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.logging.CorrelationIds;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.domain.StatusChange;
import com.veritrade.ingestion.service.FilingNotFoundException;
import com.veritrade.ingestion.service.FilingStatusService;
import com.veritrade.ingestion.service.StatusUpdate;
import com.veritrade.ingestion.support.Contracts;
import com.veritrade.ingestion.support.TestProperties;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AnalysisEventListenerTest {

    private final FilingStatusService statusService = mock(FilingStatusService.class);
    private final AnalysisEventListener listener =
            new AnalysisEventListener(new AnalysisEventReader(JsonMapper.builder().build(), TestProperties.defaults()), statusService);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @ParameterizedTest
    @EnumSource(value = EventType.class, names = "FILING_SUBMITTED", mode = EnumSource.Mode.EXCLUDE)
    void passesEveryAnalysisEventToTheStatusService(final EventType type) {
        final UUID filingId = UUID.randomUUID();

        listener.onMessage(message(Contracts.exampleFor(type, filingId).toString(), null));

        verify(statusService).apply(new StatusUpdate(EventIds.forFiling(filingId, type), filingId, target(type),
                type == EventType.ANALYSIS_FAILED ? "Analysis failed after 3 attempts: rule engine error" : null));
    }

    @Test
    void putsTheEnvelopeCorrelationIdInTheLoggingContextAndClearsItAfterwards() {
        final ObjectNode event = Contracts.exampleFor(EventType.ANALYSIS_STARTED, UUID.randomUUID());
        event.put("correlationId", "from-envelope");
        final AtomicReference<String> seen = new AtomicReference<>();
        when(statusService.apply(any())).thenAnswer(invocation -> {
            seen.set(MDC.get(CorrelationIds.MDC_KEY));
            return StatusChange.APPLIED;
        });

        listener.onMessage(message(event.toString(), "from-amqp-property"));

        assertThat(seen.get()).isEqualTo("from-envelope");
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    void clearsTheLoggingContextWhenTheMessageIsInvalid() {
        assertThatThrownBy(() -> listener.onMessage(message("garbage", "from-amqp-property")))
                .isInstanceOf(InvalidEventException.class);

        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
        verifyNoInteractions(statusService);
    }

    @Test
    void lateOrContradictoryEventReturnsNormallySoItIsAcknowledged() {
        when(statusService.apply(any())).thenReturn(StatusChange.REJECTED);

        assertThatCode(() -> listener.onMessage(
                message(Contracts.exampleFor(EventType.ANALYSIS_STARTED, UUID.randomUUID()).toString(), null)))
                .doesNotThrowAnyException();
    }

    @Test
    void duplicateEventReturnsNormally() {
        when(statusService.apply(any())).thenReturn(StatusChange.DUPLICATE);

        assertThatCode(() -> listener.onMessage(
                message(Contracts.exampleFor(EventType.ANALYSIS_COMPLETED, UUID.randomUUID()).toString(), null)))
                .doesNotThrowAnyException();
    }

    @Test
    void eventForAnUnknownFilingIsInvalid() {
        final UUID filingId = UUID.randomUUID();
        when(statusService.apply(any())).thenThrow(new FilingNotFoundException(filingId));

        assertThatThrownBy(() -> listener.onMessage(
                message(Contracts.exampleFor(EventType.ANALYSIS_COMPLETED, filingId).toString(), null)))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining(filingId.toString());
    }

    @Test
    void transientFailurePropagatesSoTheListenerRetryCanRunAgain() {
        when(statusService.apply(any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> listener.onMessage(
                message(Contracts.exampleFor(EventType.ANALYSIS_STARTED, UUID.randomUUID()).toString(), null)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(InvalidEventException.isUnprocessable(e)).isFalse());
    }

    private static FilingStatus target(final EventType type) {
        return switch (type) {
            case ANALYSIS_STARTED -> FilingStatus.ANALYZING;
            case ANALYSIS_COMPLETED -> FilingStatus.COMPLETED;
            default -> FilingStatus.FAILED;
        };
    }

    private static Message message(final String body, final String correlationId) {
        final MessageBuilder builder = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8));
        if (correlationId != null) {
            builder.setCorrelationId(correlationId);
        }
        return builder.build();
    }
}
