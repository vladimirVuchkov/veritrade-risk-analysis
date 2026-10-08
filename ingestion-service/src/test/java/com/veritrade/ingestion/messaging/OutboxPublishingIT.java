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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

/** Submit -> outbox -> broker, with publisher confirms and returns against a real RabbitMQ. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:outbox-publishing-it;DB_CLOSE_DELAY=-1",
        "ingestion.outbox.publish-interval=100ms"
})
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class OutboxPublishingIT {

    private static final Duration WAIT = Duration.ofSeconds(15);
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

    private final List<String> declaredQueues = new ArrayList<>();
    private String testQueue;

    @AfterEach
    void removeTestQueues() {
        declaredQueues.forEach(admin::deleteQueue);
    }

    @Test
    void submittedFilingReachesTheBrokerAsAValidEventWithTheContractProperties() throws Exception {
        bindTestQueue(QueueBuilder.durable("it.filing-submitted." + UUID.randomUUID()).build());

        UUID filingId = submit("corr-it-1");
        UUID eventId = EventIds.forFiling(filingId, EventType.FILING_SUBMITTED);

        Message message = Broker.receiveMatching(rabbitTemplate, testQueue, Broker.withMessageId(eventId), WAIT);
        assertThat(message).isNotNull();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
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
    void unroutableEventStaysUnpublishedUntilAQueueIsBound(CapturedOutput output) throws Exception {
        UUID eventId = EventIds.forFiling(submit("corr-it-2"), EventType.FILING_SUBMITTED);

        assertStaysUnpublished(eventId);
        assertThat(output).contains("returned as unroutable");

        bindTestQueue(QueueBuilder.durable("it.late-consumer." + UUID.randomUUID()).build());
        assertThat(Broker.receiveMatching(rabbitTemplate, testQueue, Broker.withMessageId(eventId), WAIT)).isNotNull();
        awaitPublished(eventId);
    }

    @Test
    void negativelyConfirmedEventStaysUnpublishedAndIsSentAgainLater(CapturedOutput output) throws Exception {
        String rejecting = "it.rejecting." + UUID.randomUUID();
        bindTestQueue(QueueBuilder.durable(rejecting)
                .withArguments(Map.of("x-max-length", 0, "x-overflow", "reject-publish")).build());

        UUID eventId = EventIds.forFiling(submit("corr-it-3"), EventType.FILING_SUBMITTED);

        assertStaysUnpublished(eventId);
        assertThat(output).contains("negatively confirmed");

        bindTestQueue(QueueBuilder.durable("it.recovered." + UUID.randomUUID()).build());
        admin.deleteQueue(rejecting);
        assertThat(Broker.receiveMatching(rabbitTemplate, testQueue, Broker.withMessageId(eventId), WAIT)).isNotNull();
        awaitPublished(eventId);
    }

    private void assertStaysUnpublished(UUID eventId) {
        await().during(SEVERAL_RUNS).atMost(SEVERAL_RUNS.plusSeconds(2))
                .untilAsserted(() -> assertThat(row(eventId).publishedAt()).isNull());
    }

    private void awaitPublished(UUID eventId) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(row(eventId).publishedAt()).isNotNull());
    }

    private OutboxEvent row(UUID eventId) {
        return outbox.findById(eventId).orElseThrow();
    }

    private void bindTestQueue(Queue queue) {
        admin.declareQueue(queue);
        declaredQueues.add(queue.getName());
        admin.declareBinding(BindingBuilder.bind(queue).to(eventsExchange).with("filing.submitted"));
        testQueue = queue.getName();
    }

    private UUID submit(String correlationId) throws Exception {
        String response = mvc.perform(post("/api/filings").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", correlationId)
                        .content("{\"companyName\":\"Acme\",\"title\":\"10-K\",\"content\":\"pending litigation\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(response).get("filingId").asString());
    }
}
