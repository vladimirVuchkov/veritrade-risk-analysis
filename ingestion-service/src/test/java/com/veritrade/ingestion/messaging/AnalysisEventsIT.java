package com.veritrade.ingestion.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.ingestion.domain.Filing;
import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.repository.FilingRepository;
import com.veritrade.ingestion.service.FilingStatusService;
import com.veritrade.ingestion.support.Broker;
import com.veritrade.ingestion.support.Contracts;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.support.converter.AbstractJacksonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.node.ObjectNode;

/** Analysis events -> filing status, with the listener retry and the dead-letter queue of a real RabbitMQ. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analysis-events-it;DB_CLOSE_DELAY=-1",
        "ingestion.outbox.enabled=false"
})
@Testcontainers
class AnalysisEventsIT {

    private static final String QUEUE = "ingestion.analysis-events";
    private static final String TYPE_ID_HEADER = "__TypeId__";
    private static final String ENVELOPE_CLASS = "com.veritrade.contracts.event.EventEnvelope";
    private static final String DLQ = "ingestion.analysis-events.dlq";
    private static final int MAX_REASON_LENGTH = 1000;
    private static final Duration WAIT = Duration.ofSeconds(15);
    /** Longer than every retry back-off together (1 s + 2 s), so a retry would have happened. */
    private static final Duration LONGER_THAN_RETRIES = Duration.ofSeconds(4);
    /** Initial interval plus the next back-off: a retried message cannot be dead-lettered sooner. */
    private static final Duration FIRST_RETRY_DONE = Duration.ofSeconds(3);

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = Broker.container();

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private FilingRepository filings;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoSpyBean
    private FilingStatusService statusService;

    @MockitoSpyBean
    private AnalysisEventReader reader;

    @Test
    void analysisEventsMoveTheFilingThroughItsLifecycle() {
        UUID filingId = storedFiling();

        send(EventType.ANALYSIS_STARTED, Contracts.exampleFor(EventType.ANALYSIS_STARTED, filingId));
        awaitStatus(filingId, FilingStatus.ANALYZING);

        send(EventType.ANALYSIS_COMPLETED, Contracts.exampleFor(EventType.ANALYSIS_COMPLETED, filingId));
        awaitStatus(filingId, FilingStatus.COMPLETED);
    }

    @Test
    void contractExamplesWithAJavaTypeHeaderAreReadByEventType() {
        UUID exampleFilingId = UUID.fromString(Contracts.example(EventType.ANALYSIS_STARTED)
                .get("payload").get("filingId").asString());
        filings.save(Filing.submit(exampleFilingId, "Acme", "10-K", "text", Instant.now()));

        sendWithTypeHeader(EventType.ANALYSIS_STARTED, Contracts.exampleBytes(EventType.ANALYSIS_STARTED), ENVELOPE_CLASS);
        awaitStatus(exampleFilingId, FilingStatus.ANALYZING);
        sendWithTypeHeader(EventType.ANALYSIS_COMPLETED, Contracts.exampleBytes(EventType.ANALYSIS_COMPLETED), ENVELOPE_CLASS);
        awaitStatus(exampleFilingId, FilingStatus.COMPLETED);
        sendWithTypeHeader(EventType.ANALYSIS_FAILED, Contracts.exampleBytes(EventType.ANALYSIS_FAILED), ENVELOPE_CLASS);

        verify(statusService, timeout(WAIT.toMillis()).times(3))
                .apply(argThat(update -> update.filingId().equals(exampleFilingId)));
        assertThat(filings.findById(exampleFilingId).orElseThrow().status()).isEqualTo(FilingStatus.COMPLETED);
        assertNotDeadLettered(exampleFilingId);
    }

    @Test
    void bogusJavaTypeHeaderIsIgnored() {
        UUID filingId = storedFiling();
        ObjectNode event = Contracts.exampleFor(EventType.ANALYSIS_FAILED, filingId);

        sendWithTypeHeader(EventType.ANALYSIS_FAILED, event.toString().getBytes(StandardCharsets.UTF_8),
                "java.lang.Runtime");

        awaitStatus(filingId, FilingStatus.FAILED);
        assertNotDeadLettered(filingId);
    }

    @Test
    void javaSerializedBodyIsNeverDeserializedAndGoesToTheDeadLetterQueue() {
        String messageId = UUID.randomUUID().toString();
        Message message = MessageBuilder.withBody(new byte[] {(byte) 0xAC, (byte) 0xED, 0, 5})
                .setContentType(MessageProperties.CONTENT_TYPE_SERIALIZED_OBJECT)
                .setMessageId(messageId)
                .setHeader(TYPE_ID_HEADER, "java.lang.Runtime")
                .build();

        rabbitTemplate.send("veritrade.events", "analysis.started", message);

        assertThat(Broker.receiveMatching(rabbitTemplate, DLQ, Broker.withMessageId(messageId), WAIT)).isNotNull();
    }

    @Test
    void listenerReceivesTheRawMessageWithoutAJsonPayloadConverter() {
        AbstractMessageListenerContainer container =
                (AbstractMessageListenerContainer) listenerRegistry.getListenerContainers().iterator().next();
        Object converter = ReflectionTestUtils.invokeMethod(container.getMessageListener(), "getMessageConverter");

        assertThat(listenerRegistry.getListenerContainers()).hasSize(1);
        assertThat(converter).isNotInstanceOf(AbstractJacksonMessageConverter.class);
        assertThat(applicationContext.getBeanNamesForType(MessageConverter.class)).isEmpty();
        assertThat(rabbitTemplate.getMessageConverter()).isNotInstanceOf(AbstractJacksonMessageConverter.class);
    }

    @Test
    void failedEventStoresTheReasonEvenWithoutAStartedEvent() {
        UUID filingId = storedFiling();

        send(EventType.ANALYSIS_FAILED, Contracts.exampleFor(EventType.ANALYSIS_FAILED, filingId));

        awaitStatus(filingId, FilingStatus.FAILED);
        assertThat(filings.findById(filingId).orElseThrow().failureReason())
                .isEqualTo("Analysis failed after 3 attempts: rule engine error");
    }

    @Test
    void lateContradictoryAndDuplicateEventsAreAcknowledgedNotDeadLettered() {
        UUID filingId = storedFiling();
        send(EventType.ANALYSIS_COMPLETED, Contracts.exampleFor(EventType.ANALYSIS_COMPLETED, filingId));
        awaitStatus(filingId, FilingStatus.COMPLETED);

        send(EventType.ANALYSIS_STARTED, Contracts.exampleFor(EventType.ANALYSIS_STARTED, filingId));
        send(EventType.ANALYSIS_FAILED, Contracts.exampleFor(EventType.ANALYSIS_FAILED, filingId));
        send(EventType.ANALYSIS_COMPLETED, Contracts.exampleFor(EventType.ANALYSIS_COMPLETED, filingId));

        verify(statusService, timeout(WAIT.toMillis()).times(4)).apply(argThat(update -> update.filingId().equals(filingId)));
        verify(statusService, after(LONGER_THAN_RETRIES.toMillis()).times(4))
                .apply(argThat(update -> update.filingId().equals(filingId)));
        assertThat(filings.findById(filingId).orElseThrow().status()).isEqualTo(FilingStatus.COMPLETED);
        assertThat(filings.findById(filingId).orElseThrow().failureReason()).isNull();
        assertNotDeadLettered(filingId);
    }

    @Test
    void eventWithUnknownFieldsIsProcessed() {
        UUID filingId = storedFiling();
        ObjectNode event = Contracts.exampleFor(EventType.ANALYSIS_STARTED, filingId);
        event.put("addedLater", true);
        ((ObjectNode) event.get("payload")).put("worker", "analysis-2");

        send(EventType.ANALYSIS_STARTED, event);

        awaitStatus(filingId, FilingStatus.ANALYZING);
    }

    @Test
    void unreadableMessageGoesStraightToTheDeadLetterQueueWithoutRetries() {
        String messageId = UUID.randomUUID().toString();
        Instant sentAt = Instant.now();

        rabbitTemplate.send("veritrade.events", "analysis.started", Broker.json("this is not json", messageId));

        assertThat(Broker.receiveMatching(rabbitTemplate, DLQ, Broker.withMessageId(messageId), WAIT)).isNotNull();
        assertThat(Duration.between(sentAt, Instant.now())).isLessThan(FIRST_RETRY_DONE);
    }

    @Test
    void invalidEventIsReadOnceAndNotRetried() {
        UUID filingId = UUID.randomUUID();
        ObjectNode event = Contracts.exampleFor(EventType.ANALYSIS_FAILED, filingId);
        ((ObjectNode) event.get("payload")).remove("reason");

        assertDeadLettered(event, "analysis.failed");
        verify(reader, after(LONGER_THAN_RETRIES.toMillis()).times(1))
                .read(argThat(body -> new String(body, StandardCharsets.UTF_8).contains(filingId.toString())));
    }

    /** Contract, "Event versioning": no retries, not applied, kept in the dead-letter queue for a replay. */
    @Test
    void newerEventVersionGoesStraightToTheDeadLetterQueueAndIsNotApplied() {
        UUID filingId = storedFiling();
        ObjectNode event = Contracts.exampleFor(EventType.ANALYSIS_COMPLETED, filingId);
        event.put("eventVersion", EventEnvelope.CURRENT_VERSION + 1);
        Instant sentAt = Instant.now();

        assertDeadLettered(event, "analysis.completed");

        assertThat(Duration.between(sentAt, Instant.now())).isLessThan(FIRST_RETRY_DONE);
        verify(reader, after(LONGER_THAN_RETRIES.toMillis()).times(1))
                .read(argThat(body -> new String(body, StandardCharsets.UTF_8).contains(filingId.toString())));
        assertThat(filings.findById(filingId).orElseThrow().status()).isEqualTo(FilingStatus.SUBMITTED);
    }

    /** Contract, "Text limits": a reason one UTF-16 unit over the limit is dead-lettered, not cut. */
    @Test
    void failureReasonOverTheLimitGoesStraightToTheDeadLetterQueueAndIsNotApplied() {
        UUID filingId = storedFiling();
        ObjectNode event = Contracts.exampleFor(EventType.ANALYSIS_FAILED, filingId);
        ((ObjectNode) event.get("payload")).put("reason", "r".repeat(MAX_REASON_LENGTH - 1) + "\uD83D\uDCC8");
        Instant sentAt = Instant.now();

        assertDeadLettered(event, "analysis.failed");

        assertThat(Duration.between(sentAt, Instant.now())).isLessThan(FIRST_RETRY_DONE);
        verify(statusService, after(LONGER_THAN_RETRIES.toMillis()).never())
                .apply(argThat(update -> update.filingId().equals(filingId)));
        assertThat(filings.findById(filingId).orElseThrow().status()).isEqualTo(FilingStatus.SUBMITTED);
    }

    @Test
    void failureReasonOfExactlyTheLimitIsStoredWhole() {
        UUID filingId = storedFiling();
        String reason = "r".repeat(MAX_REASON_LENGTH - 2) + "\uD83D\uDCC8";
        ObjectNode event = Contracts.exampleFor(EventType.ANALYSIS_FAILED, filingId);
        ((ObjectNode) event.get("payload")).put("reason", reason);

        send(EventType.ANALYSIS_FAILED, event);

        awaitStatus(filingId, FilingStatus.FAILED);
        assertThat(filings.findById(filingId).orElseThrow().failureReason()).isEqualTo(reason);
    }

    @Test
    void unknownEventTypeGoesToTheDeadLetterQueue() {
        ObjectNode event = Contracts.exampleFor(EventType.ANALYSIS_STARTED, UUID.randomUUID());
        event.put("eventType", "ANALYSIS_PAUSED");

        assertDeadLettered(event, "analysis.paused");
    }

    @Test
    void eventWithoutFilingIdGoesToTheDeadLetterQueue() {
        ObjectNode event = Contracts.exampleFor(EventType.ANALYSIS_COMPLETED, UUID.randomUUID());
        ((ObjectNode) event.get("payload")).remove("filingId");

        assertDeadLettered(event, "analysis.completed");
    }

    @Test
    void eventForAnUnknownFilingGoesToTheDeadLetterQueue() {
        assertDeadLettered(Contracts.exampleFor(EventType.ANALYSIS_COMPLETED, UUID.randomUUID()), "analysis.completed");
    }

    @Test
    void transientFailureIsRetriedThreeTimesThenDeadLettered() {
        UUID filingId = storedFiling();
        doThrow(new IllegalStateException("database unavailable"))
                .when(statusService).apply(argThat(update -> update != null && update.filingId().equals(filingId)));

        send(EventType.ANALYSIS_STARTED, Contracts.exampleFor(EventType.ANALYSIS_STARTED, filingId));

        UUID eventId = EventIds.forFiling(filingId, EventType.ANALYSIS_STARTED);
        assertThat(Broker.receiveMatching(rabbitTemplate, DLQ, Broker.withMessageId(eventId), WAIT)).isNotNull();
        verify(statusService, timeout(WAIT.toMillis()).times(3)).apply(argThat(update -> update.filingId().equals(filingId)));
        assertThat(filings.findById(filingId).orElseThrow().status()).isEqualTo(FilingStatus.SUBMITTED);
    }

    @Test
    void topologyMatchesTheContractExactly() {
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare("veritrade.events", "topic", true, false, null);
            channel.exchangeDeclare("veritrade.dlx", "direct", true, false, null);
            channel.queueDeclare(QUEUE, true, false, false, contractArguments());
            channel.queueDeclare(DLQ, true, false, false, null);
            return null;
        });
    }

    @Test
    void aDeclarationWithOtherArgumentsIsRefused() {
        Map<String, Object> withTtl = new HashMap<>(contractArguments());
        withTtl.put("x-message-ttl", 60_000);

        assertThatThrownBy(() -> rabbitTemplate.execute(channel -> channel.queueDeclare(QUEUE, true, false, false, withTtl)))
                .hasStackTraceContaining("PRECONDITION_FAILED");
    }

    private static Map<String, Object> contractArguments() {
        return Map.of("x-dead-letter-exchange", "veritrade.dlx", "x-dead-letter-routing-key", QUEUE);
    }

    private void assertDeadLettered(ObjectNode event, String routingKey) {
        String messageId = event.get("eventId").asString();
        rabbitTemplate.send("veritrade.events", routingKey, Broker.json(event.toString(), messageId));

        Message dead = Broker.receiveMatching(rabbitTemplate, DLQ, Broker.withMessageId(messageId), WAIT);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().getHeaders()).containsKey("x-death");
    }

    private void assertNotDeadLettered(UUID filingId) {
        Message dead = Broker.receiveMatching(rabbitTemplate, DLQ,
                message -> new String(message.getBody()).contains(filingId.toString()), Duration.ofSeconds(1));
        assertThat(dead).isNull();
        assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isZero();
    }

    private void sendWithTypeHeader(EventType type, byte[] body, String typeId) {
        Message message = MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(UUID.randomUUID().toString())
                .setHeader(TYPE_ID_HEADER, typeId)
                .build();
        rabbitTemplate.send("veritrade.events", type.routingKey(), message);
    }

    private void send(EventType type, ObjectNode event) {
        rabbitTemplate.send("veritrade.events", type.routingKey(), Broker.json(event.toString(), event.get("eventId").asString()));
    }

    private void awaitStatus(UUID filingId, FilingStatus status) {
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(filings.findById(filingId).orElseThrow().status()).isEqualTo(status));
    }

    private UUID storedFiling() {
        return filings.save(Filing.submit(UUID.randomUUID(), "Acme", "10-K", "text", Instant.now())).id();
    }
}
