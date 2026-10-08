package com.veritrade.reporting;

import static com.veritrade.reporting.support.RabbitTestContainer.encode;
import static com.veritrade.reporting.support.RabbitTestContainer.management;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veritrade.reporting.support.RabbitTestContainer;
import com.veritrade.reporting.support.TestEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The broker holds exactly the topology of docs/contracts/messaging-topology.md after Reporting starts. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reporting-topology-it;DB_CLOSE_DELAY=-1")
class TopologyIT {

    private static final String VHOST = "topology-it";
    private static final String SERVER_DEFAULT_QUEUE_TYPE = "x-queue-type";

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        RabbitTestContainer.register(registry, VHOST);
    }

    @Autowired
    private AmqpAdmin admin;

    @Test
    void exchangesAreDeclaredAsDocumented() {
        JsonNode events = management("exchanges/" + VHOST + "/veritrade.events");
        JsonNode dlx = management("exchanges/" + VHOST + "/veritrade.dlx");

        assertExchange(events, "topic");
        assertExchange(dlx, "direct");
    }

    @Test
    void workQueueIsDurableClassicWithOnlyTheDeadLetterArguments() {
        JsonNode queue = management("queues/" + VHOST + "/reporting.analysis-results");

        assertThat(queue.get("durable").asBoolean()).isTrue();
        assertThat(queue.get("auto_delete").asBoolean()).isFalse();
        assertThat(queue.get("exclusive").asBoolean()).isFalse();
        assertThat(queue.get("type").asString()).isEqualTo("classic");
        assertThat(declaredArguments(queue)).isEqualTo(TestEvents.MAPPER.valueToTree(Map.of(
                "x-dead-letter-exchange", "veritrade.dlx",
                "x-dead-letter-routing-key", "reporting.analysis-results")));
    }

    @Test
    void deadLetterQueueIsDurableWithoutArguments() {
        JsonNode dlq = management("queues/" + VHOST + "/reporting.analysis-results.dlq");

        assertThat(dlq.get("durable").asBoolean()).isTrue();
        assertThat(dlq.get("auto_delete").asBoolean()).isFalse();
        assertThat(dlq.get("type").asString()).isEqualTo("classic");
        assertThat(declaredArguments(dlq).isEmpty()).isTrue();
    }

    @Test
    void workQueueIsBoundToCompletedAndFailedOnly() {
        JsonNode bindings = management("exchanges/" + VHOST + "/veritrade.events/bindings/source");

        assertThat(routingKeysTo(bindings, "reporting.analysis-results"))
                .containsExactlyInAnyOrder("analysis.completed", "analysis.failed");
    }

    @Test
    void deadLetterQueueIsBoundToTheDeadLetterExchangeByQueueName() {
        JsonNode bindings = management("exchanges/" + VHOST + "/veritrade.dlx/bindings/source");

        assertThat(routingKeysTo(bindings, "reporting.analysis-results.dlq"))
                .containsExactly("reporting.analysis-results");
    }

    @Test
    void redeclaringWithTheDocumentedArgumentsIsIdempotentAndDifferentArgumentsAreRefused() {
        Queue documented = QueueBuilder.durable("reporting.analysis-results")
                .deadLetterExchange("veritrade.dlx")
                .deadLetterRoutingKey("reporting.analysis-results")
                .build();
        Queue withTtl = QueueBuilder.durable("reporting.analysis-results")
                .deadLetterExchange("veritrade.dlx")
                .deadLetterRoutingKey("reporting.analysis-results")
                .ttl(1)
                .build();

        assertThat(admin.declareQueue(documented)).isEqualTo("reporting.analysis-results");
        assertThatThrownBy(() -> admin.declareQueue(withTtl)).isInstanceOf(RuntimeException.class);
        assertThat(declaredArguments(management("queues/" + VHOST + "/" + encode("reporting.analysis-results"))).size())
                .isEqualTo(2);
    }

    /**
     * The queue arguments without {@code x-queue-type: classic}, which RabbitMQ 4 adds on the server side
     * as the default queue type; Reporting does not declare it.
     */
    private static ObjectNode declaredArguments(JsonNode queue) {
        ObjectNode arguments = ((ObjectNode) queue.get("arguments")).deepCopy();
        if ("classic".equals(arguments.path(SERVER_DEFAULT_QUEUE_TYPE).asString())) {
            arguments.remove(SERVER_DEFAULT_QUEUE_TYPE);
        }
        return arguments;
    }

    private static void assertExchange(JsonNode exchange, String type) {
        assertThat(exchange.get("type").asString()).isEqualTo(type);
        assertThat(exchange.get("durable").asBoolean()).isTrue();
        assertThat(exchange.get("auto_delete").asBoolean()).isFalse();
        assertThat(exchange.get("internal").asBoolean()).isFalse();
        assertThat(exchange.get("arguments").isEmpty()).isTrue();
    }

    private static List<String> routingKeysTo(JsonNode bindings, String queue) {
        List<String> keys = new ArrayList<>();
        bindings.forEach(binding -> {
            if (queue.equals(binding.get("destination").asString())) {
                keys.add(binding.get("routing_key").asString());
            }
        });
        return keys;
    }
}
