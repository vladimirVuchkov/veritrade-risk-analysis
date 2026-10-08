package com.veritrade.analysis.messaging;

import com.veritrade.analysis.domain.AnalysisResult;
import com.veritrade.analysis.engine.RiskAnalyzer;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.FilingSubmittedPayload;
import com.veritrade.contracts.logging.CorrelationIds;
import com.veritrade.contracts.messaging.MessagingTopology;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code filing.submitted}: publishes {@code analysis.started}, runs the rules and publishes
 * {@code analysis.completed}. An invalid message throws {@link InvalidFilingMessageException} (no retry,
 * dead-letter queue); any other exception is retried and finally handled by {@link FailedAnalysisRecoverer}.
 */
@Component
public class FilingSubmittedListener {

    private static final Logger log = LoggerFactory.getLogger(FilingSubmittedListener.class);

    private final FilingSubmittedReader reader;
    private final RiskAnalyzer analyzer;
    private final AnalysisEventFactory events;
    private final AnalysisEventPublisher publisher;

    public FilingSubmittedListener(FilingSubmittedReader reader, RiskAnalyzer analyzer,
            AnalysisEventFactory events, AnalysisEventPublisher publisher) {
        this.reader = reader;
        this.analyzer = analyzer;
        this.events = events;
        this.publisher = publisher;
    }

    @RabbitListener(queues = MessagingTopology.Q_ANALYSIS_FILING_SUBMITTED)
    public void onFilingSubmitted(Message message) {
        EventEnvelope<FilingSubmittedPayload> event = reader.read(message);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIds.MDC_KEY, event.correlationId())) {
            analyze(event, Boolean.TRUE.equals(message.getMessageProperties().getRedelivered()));
        }
    }

    private void analyze(EventEnvelope<FilingSubmittedPayload> event, boolean redelivered) {
        UUID filingId = event.payload().filingId();
        log.info("Analysing filing {} (event {}, redelivered {})", filingId, event.eventId(), redelivered);
        publisher.publish(events.started(filingId, event.correlationId()));
        AnalysisResult result = analyzer.analyze(event.payload().content());
        publisher.publish(events.completed(filingId, event.correlationId(), result));
        log.info("Filing {} analysed: {} findings, overall risk {}",
                filingId, result.totalFindings(), result.overallRiskLevel());
    }
}
