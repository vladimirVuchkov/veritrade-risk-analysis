package com.veritrade.ingestion.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Broker helpers for the integration tests. */
public final class Broker {

    /** The image of docker-compose.yml. */
    public static final String IMAGE = "rabbitmq:4.3-management-alpine";

    private static final Duration POLL = Duration.ofMillis(200);

    private Broker() {
    }

    public static RabbitMQContainer container() {
        return new RabbitMQContainer(IMAGE);
    }

    public static Message json(String body, String messageId) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(messageId)
                .build();
    }

    /** Receives from {@code queue} until a message matches, dropping the others; null when none arrives in time. */
    public static Message receiveMatching(RabbitTemplate template, String queue, Predicate<Message> match, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Message message = template.receive(queue, POLL.toMillis());
            if (message != null && match.test(message)) {
                return message;
            }
        }
        return null;
    }

    public static Predicate<Message> withMessageId(Object id) {
        return message -> id.toString().equals(message.getMessageProperties().getMessageId());
    }
}
