package com.veritrade.analysis.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.analysis.support.ContractFixtures;
import com.veritrade.analysis.support.RabbitIntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/** The broker holds exactly the topology of docs/contracts/messaging-topology.md (read through the management API). */
class TopologyIT extends RabbitIntegrationTest {

    private static final int HTTP_OK = 200;
    private static final String QUEUE_TYPE_ARGUMENT = "x-queue-type";

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void workQueueHasExactlyTheDocumentedProperties() throws Exception {
        final JsonNode queue = get("/api/queues/%2F/analysis.filing-submitted");

        assertThat(queue.get("durable").asBoolean()).isTrue();
        assertThat(queue.get("auto_delete").asBoolean()).isFalse();
        assertThat(queue.get("exclusive").asBoolean()).isFalse();
        assertThat(queue.get("type").asString()).isEqualTo("classic");
        assertThat(declaredArguments(queue)).isEqualTo(Map.of(
                "x-dead-letter-exchange", "veritrade.dlx",
                "x-dead-letter-routing-key", "analysis.filing-submitted"));
    }

    @Test
    void deadLetterQueueIsDurableWithoutArguments() throws Exception {
        final JsonNode dlq = get("/api/queues/%2F/analysis.filing-submitted.dlq");

        assertThat(dlq.get("durable").asBoolean()).isTrue();
        assertThat(dlq.get("type").asString()).isEqualTo("classic");
        assertThat(declaredArguments(dlq)).isEmpty();
    }

    @Test
    void exchangesAreDeclaredWithTheirTypes() throws Exception {
        final JsonNode events = get("/api/exchanges/%2F/veritrade.events");
        final JsonNode deadLetters = get("/api/exchanges/%2F/veritrade.dlx");

        assertThat(events.get("type").asString()).isEqualTo("topic");
        assertThat(events.get("durable").asBoolean()).isTrue();
        assertThat(events.get("auto_delete").asBoolean()).isFalse();
        assertThat(deadLetters.get("type").asString()).isEqualTo("direct");
        assertThat(deadLetters.get("durable").asBoolean()).isTrue();
    }

    @Test
    void queuesAreBoundWithTheDocumentedKeys() throws Exception {
        final JsonNode work = get("/api/bindings/%2F/e/veritrade.events/q/analysis.filing-submitted");
        final JsonNode deadLetter = get("/api/bindings/%2F/e/veritrade.dlx/q/analysis.filing-submitted.dlq");

        assertThat(work.valueStream().map(b -> b.get("routing_key").asString())).containsExactly("filing.submitted");
        assertThat(deadLetter.valueStream().map(b -> b.get("routing_key").asString()))
                .containsExactly("analysis.filing-submitted");
    }

    /** Queue arguments without the default queue type, which RabbitMQ 4 reports even when none was declared. */
    private static Map<String, Object> declaredArguments(final JsonNode queue) {
        final Map<String, Object> arguments = new HashMap<>(ContractFixtures.MAPPER.convertValue(
                queue.get("arguments"), new TypeReference<Map<String, Object>>() { }));
        assertThat(arguments.remove(QUEUE_TYPE_ARGUMENT)).isIn(null, "classic");
        return arguments;
    }

    private JsonNode get(final String path) throws IOException, InterruptedException {
        final String credentials = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        final HttpRequest request = HttpRequest.newBuilder(URI.create(RABBIT.getHttpUrl() + path))
                .header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .GET()
                .build();
        final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(HTTP_OK);
        return ContractFixtures.MAPPER.readTree(response.body());
    }
}
