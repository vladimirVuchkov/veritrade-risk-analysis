package com.veritrade.e2e.support;

import static com.veritrade.contracts.messaging.MessagingTopology.EVENTS_EXCHANGE;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The RabbitMQ management HTTP API: publishes test messages, reads queues and peeks at dead-letter
 * queues. The management port is looked up again when the broker container was restarted.
 */
public final class Broker {

    private static final String VHOST = "%2F";
    private static final int PERSISTENT = 2;
    /** Enough for every message a dead-letter queue collects during one run. */
    private static final int PEEK_COUNT = 10_000;
    private static final int PEEK_TRUNCATE_BYTES = 100_000;
    private static final String DEFAULT_CREDENTIAL = "veritrade";
    private static final int STATUS_CLASS = 100;
    private static final int SUCCESS_CLASS = 2;

    private final Supplier<URI> endpointLookup;
    private final String authorization;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Timeouts.HTTP_REQUEST).build();
    private volatile URI endpoint;

    public Broker(final Supplier<URI> endpointLookup) {
        this.endpointLookup = endpointLookup;
        this.endpoint = endpointLookup.get();
        final String user = System.getenv().getOrDefault("RABBITMQ_USERNAME", DEFAULT_CREDENTIAL);
        final String password = System.getenv().getOrDefault("RABBITMQ_PASSWORD", DEFAULT_CREDENTIAL);
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** Publishes to {@code veritrade.events} and asserts that the broker routed the message to a queue. */
    public void publish(final String routingKey, final String body, final MessageProperties properties) {
        final ObjectNode request = Json.object();
        request.put("routing_key", routingKey);
        request.put("payload", body);
        request.put("payload_encoding", "string");
        request.set("properties", properties.toJson(PERSISTENT));
        final JsonNode answer = send("POST", "/api/exchanges/" + VHOST + "/" + EVENTS_EXCHANGE + "/publish", request);
        assertThat(answer.path("routed").asBoolean()).as("message to %s routed", routingKey).isTrue();
    }

    /** Publishes an event with the AMQP properties a contract producer sets. */
    public void publishEvent(final JsonNode event) {
        publishEvent(event, MessageProperties.contract(event.path("eventId").asString(),
                event.path("correlationId").asString()));
    }

    /** Publishes an event with its contract routing key and the given properties. */
    public void publishEvent(final JsonNode event, final MessageProperties properties) {
        publish(Events.typeOf(event).routingKey(), Json.write(event), properties);
    }

    public JsonNode queue(final String name) {
        return send("GET", "/api/queues/" + VHOST + "/" + name, null);
    }

    public JsonNode queues() {
        return send("GET", "/api/queues/" + VHOST, null);
    }

    public JsonNode exchange(final String name) {
        return send("GET", "/api/exchanges/" + VHOST + "/" + name, null);
    }

    public JsonNode bindingsFrom(final String exchange) {
        return send("GET", "/api/exchanges/" + VHOST + "/" + exchange + "/bindings/source", null);
    }

    public JsonNode consumers() {
        return send("GET", "/api/consumers/" + VHOST, null);
    }

    /** Messages counted by the management statistics (refreshed every few seconds). */
    public long messageCount(final String queue) {
        return queue(queue).path("messages").asLong();
    }

    /** Every message of a queue without a consumer, read and requeued. Use only on dead-letter queues. */
    public List<JsonNode> peek(final String queue) {
        final ObjectNode request = Json.object();
        request.put("count", PEEK_COUNT);
        request.put("ackmode", "ack_requeue_true");
        request.put("encoding", "auto");
        request.put("truncate", PEEK_TRUNCATE_BYTES);
        final List<JsonNode> messages = new ArrayList<>();
        send("POST", "/api/queues/" + VHOST + "/" + queue + "/get", request).forEach(messages::add);
        return messages;
    }

    /** Messages of a dead-letter queue whose body or message id contains the token. */
    public List<JsonNode> peekMatching(final String queue, final String token) {
        return peek(queue).stream()
                .filter(message -> message.path("payload").asString().contains(token)
                        || message.path("properties").path("message_id").asString("").contains(token))
                .toList();
    }

    public void purge(final String queue) {
        send("DELETE", "/api/queues/" + VHOST + "/" + queue + "/contents", null);
    }

    /** A failed connection usually means the broker was restarted on a new host port: look it up and try again. */
    private JsonNode send(final String method, final String path, final JsonNode body) {
        try {
            return sendOnce(method, path, body);
        } catch (final IOException firstFailure) {
            endpoint = endpointLookup.get();
            try {
                return sendOnce(method, path, body);
            } catch (final IOException e) {
                e.addSuppressed(firstFailure);
                throw new UncheckedIOException(e);
            }
        }
    }

    private JsonNode sendOnce(final String method, final String path, final JsonNode body) throws IOException {
        final HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Timeouts.HTTP_REQUEST)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(Json.write(body)))
                .build();
        try {
            final var response = client.send(request, BodyHandlers.ofString());
            assertThat(response.statusCode() / STATUS_CLASS).as("%s %s: %s", method, path, response.body())
                    .isEqualTo(SUCCESS_CLASS);
            return response.body().isEmpty() ? Json.object() : Json.parse(response.body());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
