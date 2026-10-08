package com.veritrade.analysis.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.MessagingTopology;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;

/**
 * Runs the whole service against a real RabbitMQ (Testcontainers, dynamic ports). One broker is
 * shared by all integration tests; every test class gets a fresh application context, which is closed
 * afterwards so that only one listener consumes the work queue at a time. A capture queue bound to
 * {@code analysis.*} plays the role of the downstream consumers.
 */
@SpringBootTest(properties = {
    "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
    "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class RabbitIntegrationTest {

    protected static final String CAPTURE_QUEUE = "it.analysis-events";
    protected static final String DEAD_LETTER_QUEUE =
            MessagingTopology.deadLetterQueue(MessagingTopology.Q_ANALYSIS_FILING_SUBMITTED);
    protected static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(10);
    protected static final Duration QUIET_PERIOD = Duration.ofSeconds(1);

    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    static {
        RABBIT.start();
    }

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    @Autowired
    protected AmqpAdmin admin;

    @DynamicPropertySource
    static void rabbitProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @BeforeEach
    void prepareQueues() {
        final Queue capture = new Queue(CAPTURE_QUEUE, true, false, false);
        admin.declareQueue(capture);
        admin.declareBinding(BindingBuilder.bind(capture)
                .to(new TopicExchange(MessagingTopology.EVENTS_EXCHANGE))
                .with(MessagingTopology.RK_ANALYSIS_ALL));
        admin.purgeQueue(CAPTURE_QUEUE, false);
        admin.purgeQueue(DEAD_LETTER_QUEUE, false);
    }

    protected void sendFilingSubmitted(final String body) {
        sendFilingSubmitted(body, Map.of());
    }

    protected void sendFilingSubmitted(final String body, final Map<String, Object> headers) {
        final MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        headers.forEach(properties::setHeader);
        rabbitTemplate.send(MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.RK_FILING_SUBMITTED,
                new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    protected Message receive(final String queue) {
        final Message message = rabbitTemplate.receive(queue, RECEIVE_TIMEOUT.toMillis());
        assertThat(message).as("a message on " + queue).isNotNull();
        return message;
    }

    /** Receives analysis events until one of the given terminal type arrives; returns all of them. */
    protected List<Message> receiveEventsUntil(final EventType terminal) {
        final List<Message> received = new ArrayList<>();
        Message message;
        do {
            message = receive(CAPTURE_QUEUE);
            received.add(message);
        } while (!eventType(message).equals(terminal));
        return received;
    }

    protected void assertNoMoreMessages(final String queue) {
        assertThat(rabbitTemplate.receive(queue, QUIET_PERIOD.toMillis())).as("no message on " + queue).isNull();
    }

    protected static JsonNode json(Message message) {
        return ContractFixtures.MAPPER.readTree(message.getBody());
    }

    protected static EventType eventType(Message message) {
        return EventType.valueOf(json(message).get("eventType").asString());
    }
}
