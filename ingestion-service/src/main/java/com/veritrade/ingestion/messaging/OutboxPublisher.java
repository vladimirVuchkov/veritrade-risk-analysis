package com.veritrade.ingestion.messaging;

import com.veritrade.contracts.logging.CorrelationIds;
import com.veritrade.contracts.messaging.MessagingTopology;
import com.veritrade.ingestion.config.IngestionProperties;
import com.veritrade.ingestion.domain.OutboxEvent;
import com.veritrade.ingestion.service.OutboxService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends unpublished outbox rows in {@code created_at} order. A row is marked published only after a
 * positive publisher confirm without a return; on a nack, a return, a timeout or a broker error the run
 * stops, so the row and every row after it are sent again on the next run (at-least-once, in order).
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxService outbox;
    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;
    private final int batchSize;

    public OutboxPublisher(OutboxService outbox, RabbitTemplate rabbitTemplate, IngestionProperties properties) {
        this.outbox = outbox;
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = properties.outbox().confirmTimeout();
        this.batchSize = properties.outbox().batchSize();
    }

    public void publishPending() {
        List<OutboxEvent> batch;
        do {
            batch = outbox.nextBatch();
            if (!publishInOrder(batch)) {
                return;
            }
        } while (batch.size() == batchSize);
    }

    private boolean publishInOrder(List<OutboxEvent> batch) {
        for (OutboxEvent event : batch) {
            if (!publish(event)) {
                return false;
            }
        }
        return true;
    }

    private boolean publish(OutboxEvent event) {
        MDC.put(CorrelationIds.MDC_KEY, event.correlationId());
        try {
            CorrelationData correlation = new CorrelationData(event.id().toString());
            rabbitTemplate.send(MessagingTopology.EVENTS_EXCHANGE, event.routingKey(), toMessage(event), correlation);
            if (!isConfirmed(correlation)) {
                return false;
            }
            outbox.markPublished(event.id());
            log.info("Published event {} with routing key {}", event.id(), event.routingKey());
            return true;
        } catch (AmqpException e) {
            log.warn("Event {} not published, will retry: {}", event.id(), e.getMessage());
            return false;
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }

    private boolean isConfirmed(CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                log.warn("Event {} negatively confirmed, will retry: {}", correlation.getId(), confirm.reason());
                return false;
            }
            if (correlation.getReturned() != null) {
                log.warn("Event {} returned as unroutable, will retry: {}", correlation.getId(),
                        correlation.getReturned().getReplyText());
                return false;
            }
            return true;
        } catch (TimeoutException | ExecutionException e) {
            log.warn("No confirm for event {}, will retry: {}", correlation.getId(), e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Message toMessage(OutboxEvent event) {
        return MessageBuilder.withBody(event.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(event.id().toString())
                .setCorrelationId(event.correlationId())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }
}
