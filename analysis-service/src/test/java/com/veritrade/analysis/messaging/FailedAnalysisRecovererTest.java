package com.veritrade.analysis.messaging;

import static com.veritrade.analysis.support.TestMessages.filingSubmitted;
import static com.veritrade.analysis.support.TestMessages.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.veritrade.analysis.support.TestMessages;
import com.veritrade.contracts.event.AnalysisFailedPayload;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import tools.jackson.databind.json.JsonMapper;

class FailedAnalysisRecovererTest {

    private final AnalysisEventPublisher publisher = mock(AnalysisEventPublisher.class);
    private final FailedAnalysisRecoverer recoverer = new FailedAnalysisRecoverer(
            new FilingSubmittedReader(JsonMapper.builder().build()), new AnalysisEventFactory(Clock.systemUTC(), TestMessages.MESSAGING), publisher);

    @Test
    void publishesAnalysisFailedAfterAProcessingFailureAndReturnsNormally() {
        final UUID filingId = UUID.randomUUID();
        final Message message = message(filingSubmitted(filingId));

        recoverer.recover(message, listenerFailure(message, new IllegalStateException("rule engine error")));

        final EventEnvelope<?> failed = publishedEvent();
        assertThat(failed.eventType()).isEqualTo(EventType.ANALYSIS_FAILED);
        assertThat(failed.eventId()).isEqualTo(EventIds.forFiling(filingId, EventType.ANALYSIS_FAILED));
        assertThat(failed.correlationId()).isEqualTo("c0a8012e-5b1f-4d3c-8e2a-7f6b9d4c1a20");
        final AnalysisFailedPayload payload = (AnalysisFailedPayload) failed.payload();
        assertThat(payload.filingId()).isEqualTo(filingId);
        assertThat(payload.reason())
                .isEqualTo("Analysis failed after all retries: IllegalStateException: rule engine error");
    }

    @Test
    void namesTheExceptionTypeWhenTheFailureHasNoMessage() {
        final Message message = message(filingSubmitted(UUID.randomUUID()));

        recoverer.recover(message, listenerFailure(message, new NullPointerException()));

        assertThat(((AnalysisFailedPayload) publishedEvent().payload()).reason())
                .isEqualTo("Analysis failed after all retries: NullPointerException");
    }

    @Test
    void reportsAPublishFailureOfTheCompletedEventAsTheReason() {
        final Message message = message(filingSubmitted(UUID.randomUUID()));
        final EventEnvelope<?> event = new AnalysisEventFactory(Clock.systemUTC(), TestMessages.MESSAGING).started(UUID.randomUUID(), "c");

        recoverer.recover(message, listenerFailure(message, new EventPublishException(event, "no confirm")));

        assertThat(((AnalysisFailedPayload) publishedEvent().payload()).reason()).contains("no confirm");
    }

    @Test
    void rejectsAnInvalidMessageToTheDeadLetterQueueWithoutPublishing() {
        final Message poison = message("{not json", false);

        assertThatThrownBy(() -> recoverer.recover(poison,
                listenerFailure(poison, new InvalidFilingMessageException("Unreadable message body"))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(publisher);
    }

    @Test
    void deadLettersTheMessageWhenAnalysisFailedCannotBePublished() {
        final Message message = message(filingSubmitted(UUID.randomUUID()));
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(any());

        assertThatThrownBy(() -> recoverer.recover(message, listenerFailure(message, new IllegalStateException("x"))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasRootCauseMessage("broker down");
    }

    private EventEnvelope<?> publishedEvent() {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<EventEnvelope<?>> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }

    private static ListenerExecutionFailedException listenerFailure(final Message message, final Throwable cause) {
        return new ListenerExecutionFailedException("Listener threw exception", cause, message);
    }
}
