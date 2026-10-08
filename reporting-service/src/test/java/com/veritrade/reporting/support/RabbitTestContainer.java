package com.veritrade.reporting.support;

import com.veritrade.contracts.event.EventEnvelope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;

/**
 * One RabbitMQ broker for all integration tests (singleton container, dynamic ports), plus helpers to
 * publish events the way the producers do and to read the management API.
 */
public final class RabbitTestContainer {

    public static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    static {
        RABBIT.start();
    }

    private RabbitTestContainer() {
    }

    /**
     * Connects a test context to its own virtual host. Spring caches test contexts, and their listeners
     * stay alive, so test classes sharing a virtual host would compete for the messages of one queue.
     */
    public static void register(final DynamicPropertyRegistry registry, final String virtualHost) {
        connectionProperties(virtualHost).forEach((name, value) -> registry.add(name, () -> value));
    }

    public static Map<String, Object> connectionProperties(final String virtualHost) {
        createVirtualHost(virtualHost);
        return Map.of(
                "spring.rabbitmq.host", RABBIT.getHost(),
                "spring.rabbitmq.port", RABBIT.getAmqpPort(),
                "spring.rabbitmq.username", RABBIT.getAdminUsername(),
                "spring.rabbitmq.password", RABBIT.getAdminPassword(),
                "spring.rabbitmq.virtual-host", virtualHost);
    }

    private static void createVirtualHost(final String virtualHost) {
        try {
            RABBIT.execInContainer("rabbitmqctl", "add_vhost", virtualHost);
            RABBIT.execInContainer("rabbitmqctl", "set_permissions", "-p", virtualHost,
                    RABBIT.getAdminUsername(), ".*", ".*", ".*");
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** An AMQP message with the producer properties of messaging-topology.md. */
    public static Message producerMessage(final String json, final UUID messageId, final String correlationId) {
        final MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (messageId != null) {
            properties.setMessageId(messageId.toString());
        }
        properties.setCorrelationId(correlationId);
        return MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8)).andProperties(properties).build();
    }

    public static Message producerMessage(final EventEnvelope<?> envelope) {
        return producerMessage(
                TestEvents.MAPPER.writeValueAsString(envelope), envelope.eventId(), envelope.correlationId());
    }

    /** GET on the management HTTP API, e.g. {@code queues/<vhost>/reporting.analysis-results}. */
    public static JsonNode management(final String path) {
        final String credentials = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        final HttpRequest request = HttpRequest.newBuilder(URI.create(RABBIT.getHttpUrl() + "/api/" + path))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return TestEvents.MAPPER.readTree(response.body());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static String encode(final String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8);
    }
}
