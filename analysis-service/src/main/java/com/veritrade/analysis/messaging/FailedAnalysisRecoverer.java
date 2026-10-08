package com.veritrade.analysis.messaging;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.FilingSubmittedPayload;
import com.veritrade.contracts.logging.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

/**
 * Runs when the listener retry gives up. Implements the two failure paths of the contract:
 * <ol>
 *   <li>invalid message: rethrown as a rejection, so it goes to the dead-letter queue (it reaches
 *       this point at once, because the retry policy does not retry it);</li>
 *   <li>processing failure after the last attempt: publishes {@code analysis.failed} and returns
 *       normally, so the original message is acknowledged.</li>
 * </ol>
 * If even {@code analysis.failed} cannot be published, the message is dead-lettered rather than lost.
 */
@Component
public class FailedAnalysisRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(FailedAnalysisRecoverer.class);
    private static final String REASON_PREFIX = "Analysis failed after all retries: ";

    private final FilingSubmittedReader reader;
    private final AnalysisEventFactory events;
    private final AnalysisEventPublisher publisher;

    public FailedAnalysisRecoverer(
            final FilingSubmittedReader reader, final AnalysisEventFactory events, final AnalysisEventPublisher publisher) {
        this.reader = reader;
        this.events = events;
        this.publisher = publisher;
    }

    @Override
    public void recover(final Message message, final Throwable cause) {
        if (InvalidFilingMessageException.isCauseOf(cause)) {
            log.warn("Sending invalid message {} to the dead-letter queue: {}",
                    message.getMessageProperties().getMessageId(), mostSpecificMessage(cause));
            throw new AmqpRejectAndDontRequeueException("Invalid filing.submitted message", cause);
        }
        final EventEnvelope<FilingSubmittedPayload> event = reader.read(message);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIds.MDC_KEY, event.correlationId())) {
            publishFailure(event, cause);
        }
    }

    private void publishFailure(final EventEnvelope<FilingSubmittedPayload> event, final Throwable cause) {
        log.error("Analysis of filing {} failed after all retries", event.payload().filingId(), cause);
        try {
            publisher.publish(events.failed(event.payload().filingId(), event.correlationId(),
                    REASON_PREFIX + mostSpecificMessage(cause)));
        } catch (final RuntimeException e) {
            log.error("Could not publish analysis.failed for filing {}; dead-lettering the message",
                    event.payload().filingId(), e);
            throw new AmqpRejectAndDontRequeueException("analysis.failed could not be published", e);
        }
    }

    private static String mostSpecificMessage(final Throwable cause) {
        final Throwable specific = NestedExceptionUtils.getMostSpecificCause(cause);
        final String detail = specific.getMessage();
        return detail == null || detail.isBlank()
                ? specific.getClass().getSimpleName()
                : specific.getClass().getSimpleName() + ": " + detail;
    }
}
