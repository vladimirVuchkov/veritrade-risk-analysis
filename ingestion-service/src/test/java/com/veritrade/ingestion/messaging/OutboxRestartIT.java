package com.veritrade.ingestion.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.ingestion.IngestionApplication;
import com.veritrade.ingestion.repository.OutboxRepository;
import com.veritrade.ingestion.service.FilingService;
import com.veritrade.ingestion.service.FilingSubmission;
import com.veritrade.ingestion.support.Broker;
import com.veritrade.ingestion.support.Contracts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** An event that was not confirmed before the service stopped is published after the restart. */
@Testcontainers
class OutboxRestartIT {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Duration SEVERAL_RUNS = Duration.ofMillis(1500);

    @Container
    static final RabbitMQContainer RABBIT = Broker.container();

    @TempDir
    Path dataDir;

    private CachingConnectionFactory connectionFactory;
    private RabbitAdmin admin;

    @BeforeEach
    void connect() {
        connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        admin = new RabbitAdmin(connectionFactory);
    }

    @AfterEach
    void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void unsentEventIsPublishedAfterARestart() {
        UUID eventId;
        try (ConfigurableApplicationContext first = startService()) {
            final UUID filingId = first.getBean(FilingService.class)
                    .submit(new FilingSubmission("Acme", "10-K", "going concern"), "corr-restart").id();
            eventId = EventIds.forFiling(filingId, EventType.FILING_SUBMITTED);
            final OutboxRepository outbox = first.getBean(OutboxRepository.class);
            await().during(SEVERAL_RUNS).atMost(SEVERAL_RUNS.plusSeconds(2))
                    .untilAsserted(() -> assertThat(outbox.findById(eventId).orElseThrow().publishedAt()).isNull());
        }

        final Queue consumer = bindConsumerQueue();
        try (ConfigurableApplicationContext second = startService()) {
            final Message message = Broker.receiveMatching(new RabbitTemplate(connectionFactory), consumer.getName(),
                    Broker.withMessageId(eventId), WAIT);

            assertThat(message).isNotNull();
            assertThat(Contracts.validate(EventType.FILING_SUBMITTED,
                    new String(message.getBody(), StandardCharsets.UTF_8))).isEmpty();
            final OutboxRepository outbox = second.getBean(OutboxRepository.class);
            await().atMost(WAIT).untilAsserted(
                    () -> assertThat(outbox.findById(eventId).orElseThrow().publishedAt()).isNotNull());
        }
    }

    private Queue bindConsumerQueue() {
        final Queue queue = QueueBuilder.durable("it.restart." + UUID.randomUUID()).build();
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("veritrade.events")).with("filing.submitted"));
        return queue;
    }

    private ConfigurableApplicationContext startService() {
        return new SpringApplicationBuilder(IngestionApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:file:" + dataDir.resolve("ingestion"),
                "--spring.rabbitmq.host=" + RABBIT.getHost(),
                "--spring.rabbitmq.port=" + RABBIT.getAmqpPort(),
                "--spring.rabbitmq.username=" + RABBIT.getAdminUsername(),
                "--spring.rabbitmq.password=" + RABBIT.getAdminPassword(),
                "--ingestion.outbox.publish-interval=100ms");
    }
}
