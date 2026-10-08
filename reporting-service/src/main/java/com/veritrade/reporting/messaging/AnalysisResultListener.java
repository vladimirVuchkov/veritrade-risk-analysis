package com.veritrade.reporting.messaging;

import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.AnalysisFailedPayload;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.logging.CorrelationIds;
import com.veritrade.contracts.messaging.MessagingTopology;
import com.veritrade.reporting.service.ReportService;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the single Reporting queue (analysis.completed and analysis.failed). The raw message is
 * read by {@link AnalysisEventReader}, so no Java type header and no message converter is involved.
 */
@Component
public class AnalysisResultListener {

    private final AnalysisEventReader reader;
    private final ReportService reportService;

    public AnalysisResultListener(AnalysisEventReader reader, ReportService reportService) {
        this.reader = reader;
        this.reportService = reportService;
    }

    @RabbitListener(queues = MessagingTopology.Q_REPORTING_ANALYSIS_RESULTS)
    public void onMessage(Message message) {
        putCorrelationId(message.getMessageProperties().getCorrelationId());
        try {
            EventEnvelope<?> envelope = reader.read(message);
            putCorrelationId(envelope.correlationId());
            dispatch(envelope);
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }

    private void dispatch(EventEnvelope<?> envelope) {
        switch (envelope.payload()) {
            case AnalysisCompletedPayload completed -> reportService.recordCompleted(envelope.eventId(), completed);
            case AnalysisFailedPayload failed -> reportService.recordFailed(envelope.eventId(), failed);
            default -> throw new InvalidEventException("Unsupported payload: " + envelope.eventType());
        }
    }

    private static void putCorrelationId(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            MDC.put(CorrelationIds.MDC_KEY, correlationId);
        }
    }
}
