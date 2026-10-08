package com.veritrade.ingestion.messaging;

import com.veritrade.contracts.logging.CorrelationIds;
import com.veritrade.contracts.messaging.MessagingTopology;
import com.veritrade.ingestion.service.FilingNotFoundException;
import com.veritrade.ingestion.service.FilingStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes every analysis event from the single Ingestion queue. The message is acknowledged when this
 * method returns; a transient failure is retried by the listener retry, an invalid event is dead-lettered.
 */
@Component
public class AnalysisEventListener {

    private static final Logger log = LoggerFactory.getLogger(AnalysisEventListener.class);

    private final AnalysisEventReader reader;
    private final FilingStatusService statusService;

    public AnalysisEventListener(AnalysisEventReader reader, FilingStatusService statusService) {
        this.reader = reader;
        this.statusService = statusService;
    }

    @RabbitListener(queues = MessagingTopology.Q_INGESTION_ANALYSIS_EVENTS)
    public void onMessage(Message message) {
        putCorrelationId(message.getMessageProperties().getCorrelationId());
        try {
            AnalysisEvent event = reader.read(message.getBody());
            putCorrelationId(event.correlationId());
            log.info("Received {} event {} for filing {}", event.eventType(),
                    event.statusUpdate().eventId(), event.statusUpdate().filingId());
            statusService.apply(event.statusUpdate());
        } catch (FilingNotFoundException e) {
            throw new InvalidEventException("Event for an unknown filing " + e.filingId(), e);
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }

    private static void putCorrelationId(String correlationId) {
        if (correlationId != null) {
            MDC.put(CorrelationIds.MDC_KEY, correlationId);
        }
    }
}
