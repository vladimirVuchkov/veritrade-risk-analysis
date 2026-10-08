package com.veritrade.ingestion.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.veritrade.contracts.logging.CorrelationIds;
import com.veritrade.ingestion.domain.OutboxEvent;
import com.veritrade.ingestion.service.OutboxService;
import com.veritrade.ingestion.support.MutableClock;
import com.veritrade.ingestion.support.TestProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.AmqpResourceNotAvailableException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.UncategorizedAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class OutboxPublisherTest {

    private static final int BATCH_SIZE = 3;
    private static final Instant CREATED = Instant.parse("2026-10-07T12:00:00Z");
    private static final Duration TICK = Duration.ofMillis(100);

    private final OutboxService outbox = mock(OutboxService.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final MutableClock clock = new MutableClock(CREATED);
    private final OutboxPublisher publisher = new OutboxPublisher(outbox, rabbitTemplate,
            TestProperties.withOutbox(BATCH_SIZE, Duration.ofMillis(100)), clock);

    private final Map<String, Consumer<CorrelationData>> outcomes = new HashMap<>();
    private final List<Message> sent = new ArrayList<>();
    private final List<String> correlationIdsInMdc = new ArrayList<>();

    @BeforeEach
    void captureSends() {
        when(outbox.maxAttempts()).thenReturn(TestProperties.MAX_ATTEMPTS);
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            sent.add(message);
            correlationIdsInMdc.add(MDC.get(CorrelationIds.MDC_KEY));
            outcomes.getOrDefault(correlation.getId(), OutboxPublisherTest::ack).accept(correlation);
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void doesNothingWhenThereIsNothingToSend() {
        when(outbox.nextBatch()).thenReturn(List.of());

        publisher.publishPending();

        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void sendsRowsInOrderAndMarksEachAfterAPositiveConfirm() {
        List<OutboxEvent> rows = rows(2);
        when(outbox.nextBatch()).thenReturn(rows);

        publisher.publishPending();

        assertThat(sent).extracting(m -> m.getMessageProperties().getMessageId())
                .containsExactly(rows.get(0).id().toString(), rows.get(1).id().toString());
        verify(outbox).markPublished(rows.get(0).id());
        verify(outbox).markPublished(rows.get(1).id());
    }

    @Test
    void sendsToTheEventsExchangeWithTheContractProperties() {
        OutboxEvent row = rows(1).getFirst();
        when(outbox.nextBatch()).thenReturn(List.of(row));

        publisher.publishPending();

        verify(rabbitTemplate).send(eq("veritrade.events"), eq("filing.submitted"), any(Message.class),
                any(CorrelationData.class));
        Message message = sent.getFirst();
        MessageProperties properties = message.getMessageProperties();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(row.payload());
        assertThat(properties.getMessageId()).isEqualTo(row.id().toString());
        assertThat(properties.getCorrelationId()).isEqualTo(row.correlationId());
        assertThat(properties.getContentType()).isEqualTo("application/json");
        assertThat(properties.getContentEncoding()).isEqualTo("UTF-8");
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(properties.getHeaders()).doesNotContainKey("__TypeId__");
    }

    @Test
    void putsTheRowCorrelationIdInTheLoggingContextWhileSending() {
        when(outbox.nextBatch()).thenReturn(rows(2));

        publisher.publishPending();

        assertThat(correlationIdsInMdc).containsExactly("corr-0", "corr-1");
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    void negativeConfirmKeepsTheRowAndStopsSoLaterRowsKeepTheirOrder() {
        List<OutboxEvent> rows = rows(3);
        when(outbox.nextBatch()).thenReturn(rows);
        outcomes.put(rows.get(1).id().toString(), c -> c.getFuture().complete(new CorrelationData.Confirm(false, "nack")));

        publisher.publishPending();

        verify(outbox).markPublished(rows.get(0).id());
        verify(outbox, never()).markPublished(rows.get(1).id());
        verify(outbox, never()).markPublished(rows.get(2).id());
        assertThat(sent).hasSize(2);
    }

    @Test
    void returnedMessageKeepsTheRowEvenWithAPositiveConfirm() {
        List<OutboxEvent> rows = rows(2);
        when(outbox.nextBatch()).thenReturn(rows);
        outcomes.put(rows.get(0).id().toString(), c -> {
            c.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE", "veritrade.events",
                    "filing.submitted"));
            ack(c);
        });

        publisher.publishPending();

        verify(outbox, never()).markPublished(any());
        assertThat(sent).hasSize(1);
    }

    @Test
    void missingConfirmWithinTheTimeoutKeepsTheRow() {
        List<OutboxEvent> rows = rows(1);
        when(outbox.nextBatch()).thenReturn(rows);
        outcomes.put(rows.getFirst().id().toString(), c -> { });

        publisher.publishPending();

        verify(outbox, never()).markPublished(any());
    }

    @Test
    void failedConfirmFutureKeepsTheRow() {
        List<OutboxEvent> rows = rows(1);
        when(outbox.nextBatch()).thenReturn(rows);
        outcomes.put(rows.getFirst().id().toString(),
                c -> c.getFuture().completeExceptionally(new IllegalStateException("channel closed")));

        publisher.publishPending();

        verify(outbox, never()).markPublished(any());
    }

    @Test
    void brokerUnavailableKeepsTheRowAndStops() {
        List<OutboxEvent> rows = rows(2);
        when(outbox.nextBatch()).thenReturn(rows);
        outcomes.put(rows.getFirst().id().toString(), c -> {
            throw new AmqpConnectException(new java.net.ConnectException("refused"));
        });

        publisher.publishPending();

        verify(outbox, never()).markPublished(any());
        assertThat(sent).hasSize(1);
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    void interruptionKeepsTheRowAndRestoresTheInterruptFlag() {
        List<OutboxEvent> rows = rows(1);
        when(outbox.nextBatch()).thenReturn(rows);
        outcomes.put(rows.getFirst().id().toString(), c -> Thread.currentThread().interrupt());

        publisher.publishPending();

        assertThat(Thread.interrupted()).isTrue();
        verify(outbox, never()).markPublished(any());
    }

    @Test
    void keepsReadingWhileBatchesAreFull() {
        List<OutboxEvent> full = rows(BATCH_SIZE);
        List<OutboxEvent> rest = rows(1);
        when(outbox.nextBatch()).thenReturn(full, rest);

        publisher.publishPending();

        verify(outbox, times(2)).nextBatch();
        verify(outbox, times(BATCH_SIZE + 1)).markPublished(any());
    }

    @Test
    void doesNotReadAgainAfterAFailureInAFullBatch() {
        List<OutboxEvent> full = rows(BATCH_SIZE);
        when(outbox.nextBatch()).thenReturn(full);
        outcomes.put(full.getLast().id().toString(), c -> c.getFuture().complete(new CorrelationData.Confirm(false, "nack")));

        publisher.publishPending();

        verify(outbox, times(1)).nextBatch();
    }

    static Stream<RuntimeException> failuresOfTheMessage() {
        return Stream.of(
                new UncategorizedAmqpException(new IllegalArgumentException(
                        "Short string too long; utf-8 encoded length = 256, max = 255.")),
                new IllegalArgumentException("Invalid value type"),
                new AmqpException("wrapped", new RuntimeException(new IllegalArgumentException("deep"))),
                new MessageConversionException("cannot convert"));
    }

    @ParameterizedTest
    @MethodSource("failuresOfTheMessage")
    void failureOfTheMessageIsCountedAndStopsTheRunWhileAttemptsRemain(RuntimeException failure) {
        List<OutboxEvent> rows = rows(2);
        when(outbox.nextBatch()).thenReturn(rows);
        outcomes.put(rows.getFirst().id().toString(), c -> {
            throw failure;
        });

        publisher.publishPending();

        verify(outbox).recordFailedAttempt(eq(rows.getFirst().id()), contains(failure.getMessage()));
        verify(outbox, never()).markPublished(any());
        assertThat(sent).hasSize(1);
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    void parkedRowIsSkippedAndTheRowsAfterItArePublishedInOrder(CapturedOutput output) {
        List<OutboxEvent> rows = rows(BATCH_SIZE);
        when(outbox.nextBatch()).thenReturn(rows, List.of());
        when(outbox.recordFailedAttempt(eq(rows.get(1).id()), anyString())).thenReturn(true);
        outcomes.put(rows.get(1).id().toString(), c -> {
            throw new UncategorizedAmqpException(new IllegalArgumentException("Short string too long"));
        });

        publisher.publishPending();

        assertThat(sent).extracting(m -> m.getMessageProperties().getMessageId())
                .containsExactly(rows.get(0).id().toString(), rows.get(1).id().toString(), rows.get(2).id().toString());
        verify(outbox).markPublished(rows.get(0).id());
        verify(outbox, never()).markPublished(rows.get(1).id());
        verify(outbox).markPublished(rows.get(2).id());
        assertThat(output).contains("ERROR", "Event " + rows.get(1).id() + " parked after 3 failed attempts");
    }

    static Stream<RuntimeException> brokerFailures() {
        return Stream.of(
                new AmqpConnectException(new java.net.ConnectException("refused")),
                new AmqpConnectException(new IllegalArgumentException("bad broker address")),
                new AmqpIOException(new java.io.IOException("connection reset")),
                new AmqpTimeoutException("timed out"),
                new AmqpAuthenticationException(new RuntimeException("ACCESS_REFUSED")),
                new AmqpResourceNotAvailableException("channelMax reached"),
                new UncategorizedAmqpException(new IllegalStateException("channel closed")),
                new AmqpException("unknown"));
    }

    @ParameterizedTest
    @MethodSource("brokerFailures")
    void brokerFailureIsNeverCountedAgainstTheRow(RuntimeException failure) {
        List<OutboxEvent> rows = rows(2);
        when(outbox.nextBatch()).thenReturn(rows);
        outcomes.put(rows.getFirst().id().toString(), c -> {
            throw failure;
        });

        for (int run = 0; run < TestProperties.MAX_ATTEMPTS * 2; run++) {
            publisher.publishPending();
        }

        verify(outbox, never()).recordFailedAttempt(any(), any());
        verify(outbox, never()).markPublished(any());
        assertThat(sent).extracting(m -> m.getMessageProperties().getMessageId())
                .containsOnly(rows.getFirst().id().toString());
    }

    @Test
    void confirmFailuresAreNeverCountedAgainstTheRow() {
        List<OutboxEvent> rows = rows(1);
        when(outbox.nextBatch()).thenReturn(rows);
        String id = rows.getFirst().id().toString();
        List<Consumer<CorrelationData>> confirmFailures = List.of(
                c -> c.getFuture().complete(new CorrelationData.Confirm(false, "nack")),
                c -> {
                    c.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE", "veritrade.events",
                            "filing.submitted"));
                    ack(c);
                },
                c -> { },
                c -> c.getFuture().completeExceptionally(new IllegalArgumentException("channel closed")));

        confirmFailures.forEach(failure -> {
            outcomes.put(id, failure);
            publisher.publishPending();
        });

        assertThat(sent).hasSize(confirmFailures.size());
        verify(outbox, never()).recordFailedAttempt(any(), any());
        verify(outbox, never()).markPublished(any());
    }

    /** Review W3-07: a failed run must not reload every pending payload on each scheduler tick. */
    @Test
    void readsTheOutboxAgainOnlyAfterAnExponentialBackoffWhileRunsFail() {
        OutboxPublisher publisher = withBackoff();
        when(outbox.nextBatch()).thenReturn(rows(1));
        rejectEverySend();

        List<Duration> reads = readTimes(publisher, Duration.ofSeconds(15));

        assertThat(reads).containsExactly(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofSeconds(7), Duration.ofSeconds(11), Duration.ofSeconds(15));
    }

    @Test
    void aSuccessfulRunResetsTheBackoff() {
        OutboxPublisher publisher = withBackoff();
        when(outbox.nextBatch()).thenReturn(rows(1));
        rejectEverySend();
        publisher.publishPending();
        clock.advance(Duration.ofSeconds(1));
        publisher.publishPending();
        clock.advance(Duration.ofSeconds(2));
        acceptEverySend();
        publisher.publishPending();
        rejectEverySend();
        clock.advance(TICK);
        publisher.publishPending();
        assertThat(outboxReads()).isEqualTo(4);

        clock.advance(Duration.ofSeconds(1).minus(TICK));
        publisher.publishPending();
        assertThat(outboxReads()).as("still paused").isEqualTo(4);
        clock.advance(TICK);
        publisher.publishPending();
        assertThat(outboxReads()).as("one initial back-off after the reset").isEqualTo(5);
    }

    @Test
    void readsTheOutboxOnEveryTickWhileNothingFails() {
        when(outbox.nextBatch()).thenReturn(List.of());

        assertThat(readTimes(withBackoff(), Duration.ofSeconds(1))).hasSize(11);
    }

    @Test
    void aParkedRowDoesNotStartABackoff() {
        OutboxPublisher publisher = withBackoff();
        List<OutboxEvent> rows = rows(1);
        when(outbox.nextBatch()).thenReturn(rows);
        when(outbox.recordFailedAttempt(any(), anyString())).thenReturn(true);
        outcomes.put(rows.getFirst().id().toString(), c -> {
            throw new IllegalArgumentException("Short string too long");
        });

        assertThat(readTimes(publisher, TICK)).hasSize(2);
    }

    private OutboxPublisher withBackoff() {
        return new OutboxPublisher(outbox, rabbitTemplate, TestProperties.withOutbox(BATCH_SIZE, Duration.ofMillis(100),
                Duration.ofSeconds(1), Duration.ofSeconds(4)), clock);
    }

    /** Calls the publisher on every scheduler tick for {@code total}; returns the offsets at which it read the outbox. */
    private List<Duration> readTimes(OutboxPublisher publisher, Duration total) {
        List<Duration> reads = new ArrayList<>();
        for (Duration offset = Duration.ZERO; offset.compareTo(total) <= 0; offset = offset.plus(TICK)) {
            long before = outboxReads();
            publisher.publishPending();
            if (outboxReads() > before) {
                reads.add(offset);
            }
            clock.advance(TICK);
        }
        return reads;
    }

    private long outboxReads() {
        return mockingDetails(outbox).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("nextBatch"))
                .count();
    }

    private void rejectEverySend() {
        doAnswer(invocation -> {
            ((CorrelationData) invocation.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private void acceptEverySend() {
        doAnswer(invocation -> {
            ack(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private static void ack(CorrelationData correlation) {
        correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
    }

    private static List<OutboxEvent> rows(int count) {
        List<OutboxEvent> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new OutboxEvent(UUID.randomUUID(), "filing.submitted", "corr-" + i,
                    "{\"n\":" + i + "}", CREATED.plusSeconds(i)));
        }
        return rows;
    }
}
