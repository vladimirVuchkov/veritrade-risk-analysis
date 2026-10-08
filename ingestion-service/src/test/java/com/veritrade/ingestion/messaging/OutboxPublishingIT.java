package com.veritrade.ingestion.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.ingestion.domain.OutboxEvent;
import com.veritrade.ingestion.repository.OutboxRepository;
import com.veritrade.ingestion.support.Broker;
import com.veritrade.ingestion.support.Contracts;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

/** Submit -> outbox -> broker, with publisher confirms and returns against a real RabbitMQ. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:outbox-publishing-it;DB_CLOSE_DELAY=-1",
        "ingestion.outbox.publish-interval=100ms",
        "ingestion.outbox.max-attempts=" + OutboxPublishingIT.MAX_ATTEMPTS,
        "ingestion.outbox.retry-backoff=100ms",
        "ingestion.outbox.max-retry-backoff=400ms"
})
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class OutboxPublishingIT {

    static final int MAX_ATTEMPTS = 3;
    private static final Duration WAIT = Duration.ofSeconds(15);
    /** 64 "é" sent as raw UTF-8 and decoded by Tomcat as ISO-8859-1: 128 characters, 256 bytes in UTF-8. */
    private static final String POISON_CORRELATION_ID =
            new String("\u00e9".repeat(64).getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    private static final Duration SEVERAL_RUNS = Duration.ofMillis(1500);

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = Broker.container();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private TopicExchange eventsExchange;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> declaredQueues = new ArrayList<>();
    private String testQueue;

    @AfterEach
    void removeTestQueues() {
        declaredQueues.forEach(admin::deleteQueue);
    }

    @Test
    void submittedFilingReachesTheBrokerAsAValidEventWithTheContractProperties() throws Exception {
        bindTestQueue(QueueBuilder.durable("it.filing-submitted." + UUID.randomUUID()).build());

        final UUID filingId = submit("corr-it-1");
        final UUID eventId = EventIds.forFiling(filingId, EventType.FILING_SUBMITTED);

        final Message message = Broker.receiveMatching(rabbitTemplate, testQueue, Broker.withMessageId(eventId), WAIT);
        assertThat(message).isNotNull();
        final String body = new String(message.getBody(), StandardCharsets.UTF_8);
        assertThat(Contracts.validate(EventType.FILING_SUBMITTED, body)).isEmpty();
        assertThat(Contracts.validateEnvelope(body)).isEmpty();
        assertThat(jsonMapper.readTree(body).get("payload").get("filingId").asString()).isEqualTo(filingId.toString());
        assertThat(message.getMessageProperties().getCorrelationId()).isEqualTo("corr-it-1");
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getReceivedExchange()).isEqualTo("veritrade.events");
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("filing.submitted");
        assertThat(message.getMessageProperties().getHeaders()).doesNotContainKey("__TypeId__");
        awaitPublished(eventId);
    }

    @Test
    void unroutableEventStaysUnpublishedUntilAQueueIsBound(final CapturedOutput output) throws Exception {
        final UUID eventId = EventIds.forFiling(submit("corr-it-2"), EventType.FILING_SUBMITTED);

        assertStaysUnpublished(eventId);
        assertThat(output).contains("returned as unroutable");
        assertThat(outboxColumns(eventId)).as("unroutable is transient").containsEntry("ATTEMPTS", 0)
                .containsEntry("PARKED_AT", null);

        bindTestQueue(QueueBuilder.durable("it.late-consumer." + UUID.randomUUID()).build());
        assertThat(Broker.receiveMatching(rabbitTemplate, testQueue, Broker.withMessageId(eventId), WAIT)).isNotNull();
        awaitPublished(eventId);
    }

    @Test
    void negativelyConfirmedEventStaysUnpublishedAndIsSentAgainLater(final CapturedOutput output) throws Exception {
        final String rejecting = "it.rejecting." + UUID.randomUUID();
        bindTestQueue(QueueBuilder.durable(rejecting)
                .withArguments(Map.of("x-max-length", 0, "x-overflow", "reject-publish")).build());

        final UUID eventId = EventIds.forFiling(submit("corr-it-3"), EventType.FILING_SUBMITTED);

        assertStaysUnpublished(eventId);
        assertThat(output).contains("negatively confirmed");
        assertThat(outboxColumns(eventId)).as("a nack is transient").containsEntry("ATTEMPTS", 0)
                .containsEntry("PARKED_AT", null);

        bindTestQueue(QueueBuilder.durable("it.recovered." + UUID.randomUUID()).build());
        admin.deleteQueue(rejecting);
        assertThat(Broker.receiveMatching(rabbitTemplate, testQueue, Broker.withMessageId(eventId), WAIT)).isNotNull();
        awaitPublished(eventId);
    }

    /** Review W3-01: the header that blocked the outbox for good is replaced, so the filing is published. */
    @Test
    void correlationIdOverTheAmqpShortStringLimitIsReplacedAndTheFilingIsPublished() throws Exception {
        bindTestQueue(QueueBuilder.durable("it.poison-header." + UUID.randomUUID()).build());

        final var response = mvc.perform(post("/api/filings").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", POISON_CORRELATION_ID)
                        .content("{\"companyName\":\"Acme\",\"title\":\"10-K\",\"content\":\"pending litigation\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse();
        final String generated = response.getHeader("X-Correlation-Id");
        final UUID filingId = UUID.fromString(jsonMapper.readTree(response.getContentAsString()).get("filingId").asString());
        final UUID eventId = EventIds.forFiling(filingId, EventType.FILING_SUBMITTED);

        assertThat(UUID.fromString(generated)).isNotNull();
        final Message message = Broker.receiveMatching(rabbitTemplate, testQueue, Broker.withMessageId(eventId), WAIT);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getCorrelationId()).isEqualTo(generated);
        awaitPublished(eventId);
    }

    /**
     * Review W3-01, defence in depth: a row the AMQP client can never encode is parked after exactly
     * max-attempts attempts; the filing behind it waits until then (order) and is published afterwards.
     */
    @Test
    void rowThatCanNeverBePublishedIsParkedAfterExactlyMaxAttemptsAndLaterRowsArePublished(final CapturedOutput output)
            throws Exception {
        bindTestQueue(QueueBuilder.durable("it.after-poison." + UUID.randomUUID()).build());
        final UUID poisonId = UUID.randomUUID();
        outbox.save(new OutboxEvent(poisonId, "filing.submitted", POISON_CORRELATION_ID, "{}",
                Instant.now().minusSeconds(60)));

        final UUID nextEventId = EventIds.forFiling(submit("corr-it-after-poison"), EventType.FILING_SUBMITTED);

        assertThat(Broker.receiveMatching(rabbitTemplate, testQueue, Broker.withMessageId(nextEventId), WAIT)).isNotNull();
        awaitPublished(nextEventId);
        final Map<String, Object> poison = outboxColumns(poisonId);
        assertThat(poison).containsEntry("ATTEMPTS", MAX_ATTEMPTS).containsEntry("PUBLISHED_AT", null);
        assertThat(poison.get("PARKED_AT")).isNotNull();
        assertThat((String) poison.get("LAST_ERROR")).contains("Short string too long");
        assertThat(row(nextEventId).publishedAt()).as("the later row waited for the parking (order)")
                .isAfterOrEqualTo(((OffsetDateTime) poison.get("PARKED_AT")).toInstant());
        assertThat(output).contains("attempt 1 of 3", "attempt 2 of 3", "Event " + poisonId + " parked after 3 failed attempts")
                .doesNotContain("attempt 3 of 3");
        await().during(SEVERAL_RUNS).atMost(SEVERAL_RUNS.plusSeconds(2))
                .untilAsserted(() -> assertThat(outboxColumns(poisonId)).containsEntry("ATTEMPTS", MAX_ATTEMPTS));
    }

    /** A broker outage is transient: it parks nothing and the order is kept once the broker is back. */
    @Test
    void brokerOutageParksNothingAndThePendingRowsArePublishedInOrderAfterwards(final CapturedOutput output) throws Exception {
        bindTestQueue(QueueBuilder.durable("it.outage." + UUID.randomUUID()).build());
        rabbitmqctl("stop_app");
        UUID first;
        UUID second;
        try {
            first = EventIds.forFiling(submit("corr-it-outage-1"), EventType.FILING_SUBMITTED);
            second = EventIds.forFiling(submit("corr-it-outage-2"), EventType.FILING_SUBMITTED);
            await().atMost(WAIT).until(() -> output.toString().contains("not published, will retry"));
            await().during(SEVERAL_RUNS).atMost(SEVERAL_RUNS.plusSeconds(2)).untilAsserted(() -> {
                assertThat(outboxColumns(first)).containsEntry("ATTEMPTS", 0).containsEntry("PARKED_AT", null);
                assertThat(outboxColumns(second)).containsEntry("ATTEMPTS", 0).containsEntry("PARKED_AT", null);
            });
        } finally {
            rabbitmqctl("start_app");
        }

        final Message firstMessage = Broker.receiveMatching(rabbitTemplate, testQueue, message -> true, WAIT);
        final Message secondMessage = Broker.receiveMatching(rabbitTemplate, testQueue, message -> true, WAIT);
        assertThat(firstMessage.getMessageProperties().getMessageId()).isEqualTo(first.toString());
        assertThat(secondMessage.getMessageProperties().getMessageId()).isEqualTo(second.toString());
        awaitPublished(second);
        assertThat(output).doesNotContain("parked after", "refused before reaching the broker");
    }

    private Map<String, Object> outboxColumns(final UUID eventId) {
        return jdbc.queryForMap("select attempts, last_error, parked_at, published_at from outbox where id = ?", eventId);
    }

    private static void rabbitmqctl(final String command) throws Exception {
        assertThat(RABBIT.execInContainer("rabbitmqctl", command).getExitCode()).as("rabbitmqctl %s", command).isZero();
    }

    private void assertStaysUnpublished(final UUID eventId) {
        await().during(SEVERAL_RUNS).atMost(SEVERAL_RUNS.plusSeconds(2))
                .untilAsserted(() -> assertThat(row(eventId).publishedAt()).isNull());
    }

    private void awaitPublished(final UUID eventId) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(row(eventId).publishedAt()).isNotNull());
    }

    private OutboxEvent row(final UUID eventId) {
        return outbox.findById(eventId).orElseThrow();
    }

    private void bindTestQueue(final Queue queue) {
        admin.declareQueue(queue);
        declaredQueues.add(queue.getName());
        admin.declareBinding(BindingBuilder.bind(queue).to(eventsExchange).with("filing.submitted"));
        testQueue = queue.getName();
    }

    private UUID submit(final String correlationId) throws Exception {
        final String response = mvc.perform(post("/api/filings").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", correlationId)
                        .content("{\"companyName\":\"Acme\",\"title\":\"10-K\",\"content\":\"pending litigation\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(response).get("filingId").asString());
    }
}
