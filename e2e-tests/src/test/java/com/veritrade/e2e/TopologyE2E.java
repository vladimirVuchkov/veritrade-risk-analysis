package com.veritrade.e2e;

import static com.veritrade.contracts.messaging.MessagingTopology.ARG_DEAD_LETTER_EXCHANGE;
import static com.veritrade.contracts.messaging.MessagingTopology.ARG_DEAD_LETTER_ROUTING_KEY;
import static com.veritrade.contracts.messaging.MessagingTopology.DEAD_LETTER_EXCHANGE;
import static com.veritrade.contracts.messaging.MessagingTopology.EVENTS_EXCHANGE;
import static com.veritrade.contracts.messaging.MessagingTopology.Q_ANALYSIS_FILING_SUBMITTED;
import static com.veritrade.contracts.messaging.MessagingTopology.Q_INGESTION_ANALYSIS_EVENTS;
import static com.veritrade.contracts.messaging.MessagingTopology.Q_REPORTING_ANALYSIS_RESULTS;
import static com.veritrade.contracts.messaging.MessagingTopology.RK_ANALYSIS_ALL;
import static com.veritrade.contracts.messaging.MessagingTopology.RK_ANALYSIS_COMPLETED;
import static com.veritrade.contracts.messaging.MessagingTopology.RK_ANALYSIS_FAILED;
import static com.veritrade.contracts.messaging.MessagingTopology.RK_FILING_SUBMITTED;
import static com.veritrade.contracts.messaging.MessagingTopology.deadLetterQueue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.Timeouts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Scenario 18: the live topology matches docs/contracts/messaging-topology.md. */
class TopologyE2E extends E2ETestBase {

    private static final List<String> WORK_QUEUES =
            List.of(Q_ANALYSIS_FILING_SUBMITTED, Q_INGESTION_ANALYSIS_EVENTS, Q_REPORTING_ANALYSIS_RESULTS);
    /** RabbitMQ 4 reports the default queue type as an argument although no service declares it. */
    private static final String SERVER_ADDED_QUEUE_TYPE = "x-queue-type";
    private static final String CLASSIC = "classic";
    private static final int PREFETCH = 10;

    @Test
    void exchangesAreDurableTopicAndDirect() {
        assertExchange(EVENTS_EXCHANGE, "topic");
        assertExchange(DEAD_LETTER_EXCHANGE, "direct");
    }

    @ParameterizedTest
    @ValueSource(strings = {Q_ANALYSIS_FILING_SUBMITTED, Q_INGESTION_ANALYSIS_EVENTS, Q_REPORTING_ANALYSIS_RESULTS})
    void workQueueHasExactlyTheDeadLetterArguments(final String queue) {
        final JsonNode declared = broker.queue(queue);

        assertDurableClassicQueue(declared);
        assertThat(arguments(declared)).isEqualTo(Map.of(
                ARG_DEAD_LETTER_EXCHANGE, DEAD_LETTER_EXCHANGE,
                ARG_DEAD_LETTER_ROUTING_KEY, queue));
    }

    @ParameterizedTest
    @ValueSource(strings = {Q_ANALYSIS_FILING_SUBMITTED, Q_INGESTION_ANALYSIS_EVENTS, Q_REPORTING_ANALYSIS_RESULTS})
    void deadLetterQueueHasNoArgumentsAndNoConsumer(final String workQueue) {
        final JsonNode declared = broker.queue(deadLetterQueue(workQueue));

        assertDurableClassicQueue(declared);
        assertThat(arguments(declared)).isEmpty();
        assertThat(declared.path("consumers").asInt()).isZero();
    }

    @Test
    void eventsExchangeHasExactlyTheDocumentedBindings() {
        assertThat(bindings(EVENTS_EXCHANGE)).isEqualTo(Set.of(
                Q_ANALYSIS_FILING_SUBMITTED + " <- " + RK_FILING_SUBMITTED,
                Q_INGESTION_ANALYSIS_EVENTS + " <- " + RK_ANALYSIS_ALL,
                Q_REPORTING_ANALYSIS_RESULTS + " <- " + RK_ANALYSIS_COMPLETED,
                Q_REPORTING_ANALYSIS_RESULTS + " <- " + RK_ANALYSIS_FAILED));
    }

    @Test
    void deadLetterExchangeRoutesEachWorkQueueNameToItsDeadLetterQueue() {
        assertThat(bindings(DEAD_LETTER_EXCHANGE)).isEqualTo(WORK_QUEUES.stream()
                .map(queue -> deadLetterQueue(queue) + " <- " + queue)
                .collect(Collectors.toSet()));
    }

    @Test
    void noOtherQueuesExist() {
        final Set<String> expected = WORK_QUEUES.stream()
                .flatMap(queue -> Stream.of(queue, deadLetterQueue(queue)))
                .collect(Collectors.toSet());

        assertThat(StreamSupport.stream(broker.queues().spliterator(), false)
                .map(queue -> queue.path("name").asString())).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void everyWorkQueueHasAConsumerWithPrefetchTen() {
        final Map<String, List<Integer>> prefetchByQueue = await("consumers of every work queue")
                .atMost(Timeouts.QUEUE_STATISTICS).pollInterval(Timeouts.POLL_INTERVAL)
                .until(TopologyE2E::prefetchByQueue, consumers -> consumers.keySet().containsAll(WORK_QUEUES));

        assertThat(prefetchByQueue).containsOnlyKeys(WORK_QUEUES);
        prefetchByQueue.values().forEach(prefetch -> assertThat(prefetch).isNotEmpty().containsOnly(PREFETCH));
    }

    private static Map<String, List<Integer>> prefetchByQueue() {
        return StreamSupport.stream(broker.consumers().spliterator(), false)
                .collect(Collectors.groupingBy(consumer -> consumer.path("queue").path("name").asString(),
                        Collectors.mapping(consumer -> consumer.path("prefetch_count").asInt(), Collectors.toList())));
    }

    private static void assertExchange(final String name, final String type) {
        final JsonNode exchange = broker.exchange(name);
        assertThat(exchange.path("type").asString()).isEqualTo(type);
        assertThat(exchange.path("durable").asBoolean()).isTrue();
        assertThat(exchange.path("auto_delete").asBoolean()).isFalse();
        assertThat(exchange.path("internal").asBoolean()).isFalse();
        assertThat(exchange.path("arguments").isEmpty()).isTrue();
    }

    private static void assertDurableClassicQueue(final JsonNode queue) {
        assertThat(queue.path("durable").asBoolean()).isTrue();
        assertThat(queue.path("auto_delete").asBoolean()).isFalse();
        assertThat(queue.path("exclusive").asBoolean()).isFalse();
        assertThat(queue.path("type").asString()).isEqualTo(CLASSIC);
    }

    /** Declared arguments, without the queue type that the server adds by itself. */
    private static Map<String, String> arguments(final JsonNode queue) {
        final Map<String, String> arguments = new TreeMap<>();
        queue.path("arguments").properties().forEach(entry -> arguments.put(entry.getKey(), entry.getValue().asString()));
        assertThat(arguments.getOrDefault(SERVER_ADDED_QUEUE_TYPE, CLASSIC)).isEqualTo(CLASSIC);
        arguments.remove(SERVER_ADDED_QUEUE_TYPE);
        return arguments;
    }

    private static Set<String> bindings(final String exchange) {
        return StreamSupport.stream(broker.bindingsFrom(exchange).spliterator(), false)
                .map(binding -> binding.path("destination").asString() + " <- " + binding.path("routing_key").asString())
                .collect(Collectors.toSet());
    }
}
