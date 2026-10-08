package com.veritrade.ingestion.messaging;

import com.veritrade.contracts.logging.CorrelationIds;
import com.veritrade.contracts.messaging.MessagingTopology;
import com.veritrade.ingestion.config.IngestionProperties;
import com.veritrade.ingestion.domain.OutboxEvent;
import com.veritrade.ingestion.service.OutboxService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
 * positive publisher confirm without a return.
 *
 * <p>On a nack, a return, a missing confirm or a broker failure the run stops, so the row and every row
 * after it are sent again later, in order (at-least-once). Such a failure says nothing about the row and
 * never counts against it.
 *
 * <p>A failure of the row itself (see {@link PublishFailures}: the message is refused before it reaches the
 * broker) also stops the run, but it is counted. After {@code ingestion.outbox.max-attempts} such failures the
 * row is parked: it is logged at ERROR, never sent again, and the run goes on with the next rows. Each row
 * carries one event of its own filing, so skipping it does not reorder the events of any other filing.
 *
 * <p>After a run that stopped early, the next runs are skipped for an exponential back-off
 * ({@code ingestion.outbox.retry-backoff*}), so a long outage does not reload every pending payload each tick.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private enum Outcome { PUBLISHED, PARKED, RETRY_LATER }

    private final OutboxService outbox;
    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;
    private final int batchSize;
    private final RetryBackoff backoff;

    public OutboxPublisher(OutboxService outbox, RabbitTemplate rabbitTemplate, IngestionProperties properties,
            Clock clock) {
        this.outbox = outbox;
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = properties.outbox().confirmTimeout();
        this.batchSize = properties.outbox().batchSize();
        this.backoff = new RetryBackoff(clock, properties.outbox());
    }

    public void publishPending() {
        if (backoff.isPaused()) {
            return;
        }
        if (publishAll()) {
            backoff.succeeded();
        } else {
            log.debug("Outbox run stopped early; next run in {}", backoff.failed());
        }
    }

    private boolean publishAll() {
        List<OutboxEvent> batch;
        do {
            batch = outbox.nextBatch();
            if (!publishInOrder(batch)) {
                return false;
            }
        } while (batch.size() == batchSize);
        return true;
    }

    private boolean publishInOrder(List<OutboxEvent> batch) {
        for (OutboxEvent event : batch) {
            if (publish(event) == Outcome.RETRY_LATER) {
                return false;
            }
        }
        return true;
    }

    private Outcome publish(OutboxEvent event) {
        MDC.put(CorrelationIds.MDC_KEY, event.correlationId());
        try {
            CorrelationData correlation = new CorrelationData(event.id().toString());
            rabbitTemplate.send(MessagingTopology.EVENTS_EXCHANGE, event.routingKey(), toMessage(event), correlation);
            if (!isConfirmed(correlation)) {
                return Outcome.RETRY_LATER;
            }
            outbox.markPublished(event.id());
            log.info("Published event {} with routing key {}", event.id(), event.routingKey());
            return Outcome.PUBLISHED;
        } catch (AmqpException | IllegalArgumentException e) {
            return PublishFailures.isCausedByTheMessage(e) ? countFailure(event, e) : brokerFailure(event, e);
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }

    private static Outcome brokerFailure(OutboxEvent event, RuntimeException e) {
        log.warn("Event {} not published, will retry: {}", event.id(), e.getMessage());
        return Outcome.RETRY_LATER;
    }

    private Outcome countFailure(OutboxEvent event, RuntimeException e) {
        if (outbox.recordFailedAttempt(event.id(), e.toString())) {
            log.error("Event {} parked after {} failed attempts and will not be published; later events go on: {}",
                    event.id(), outbox.maxAttempts(), e.toString());
            return Outcome.PARKED;
        }
        log.warn("Event {} refused before reaching the broker (attempt {} of {}), will retry: {}",
                event.id(), event.attempts() + 1, outbox.maxAttempts(), e.toString());
        return Outcome.RETRY_LATER;
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
