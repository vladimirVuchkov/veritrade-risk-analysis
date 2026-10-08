package com.veritrade.e2e;

import static com.veritrade.e2e.support.DeadLetters.ANALYSIS_DLQ;
import static com.veritrade.e2e.support.DeadLetters.INGESTION_DLQ;
import static com.veritrade.e2e.support.DeadLetters.REPORTING_DLQ;
import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.contracts.event.EventType;
import com.veritrade.e2e.support.DeadLetters;
import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.Events;
import com.veritrade.e2e.support.FilingRequest;
import com.veritrade.e2e.support.MessageProperties;
import com.veritrade.e2e.support.Timeouts;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scenario 13: a message that cannot be read goes straight to the dead-letter queue of every work
 * queue it was routed to, without the 3 listener attempts, and the next valid filing still flows.
 */
class PoisonMessageE2E extends E2ETestBase {

    /** Work queues (by their dead-letter queues) bound to each routing key. */
    private static final Map<EventType, List<String>> DEAD_LETTER_QUEUES = Map.of(
            EventType.FILING_SUBMITTED, List.of(ANALYSIS_DLQ),
            EventType.ANALYSIS_STARTED, List.of(INGESTION_DLQ),
            EventType.ANALYSIS_COMPLETED, List.of(INGESTION_DLQ, REPORTING_DLQ),
            EventType.ANALYSIS_FAILED, List.of(INGESTION_DLQ, REPORTING_DLQ));

    enum Poison {
        NOT_JSON {
            @Override
            String body(final EventType routedAs, final String token) {
                return "this is not JSON " + token;
            }
        },
        JSON_ARRAY {
            @Override
            String body(final EventType routedAs, final String token) {
                return "[\"" + token + "\"]";
            }
        },
        UNKNOWN_EVENT_TYPE {
            @Override
            String body(final EventType routedAs, final String token) {
                return envelope("SOMETHING_NEW", token).toString();
            }
        },
        MISSING_PAYLOAD {
            @Override
            String body(final EventType routedAs, final String token) {
                ObjectNode event = envelope(routedAs.name(), token);
                event.remove("payload");
                return event.toString();
            }
        };

        abstract String body(EventType routedAs, String token);

        private static ObjectNode envelope(final String eventType, final String token) {
            final ObjectNode event = Events.started(UUID.randomUUID(), UUID.randomUUID(), token);
            event.put("eventType", eventType);
            return event;
        }
    }

    static Stream<Arguments> poisonedRoutes() {
        return DEAD_LETTER_QUEUES.keySet().stream().sorted()
                .flatMap(type -> Arrays.stream(Poison.values()).map(poison -> Arguments.of(type, poison)));
    }

    @ParameterizedTest(name = "{1} on {0}")
    @MethodSource("poisonedRoutes")
    void poisonGoesStraightToTheMatchingDeadLetterQueueAndValidFilingsStillFlow(final EventType routedAs, final Poison poison) {
        final String token = correlationId("poison");
        final Instant published = Instant.now();

        broker.publish(routedAs.routingKey(), poison.body(routedAs, token), MessageProperties.contract(token, token));

        DEAD_LETTER_QUEUES.get(routedAs).forEach(queue -> assertDeadLetteredWithoutRetries(queue, token, published));
        DeadLetters.ALL.stream().filter(queue -> !DEAD_LETTER_QUEUES.get(routedAs).contains(queue))
                .forEach(queue -> assertThat(broker.peekMatching(queue, token)).as(queue).isEmpty());
        assertValidFilingStillFlows();
    }

    @Test
    void analysisEventForAnUnknownFilingIsDeadLetteredByIngestion() {
        final String token = correlationId("unknown-filing");
        final ObjectNode started = Events.started(UUID.randomUUID(), UUID.randomUUID(), token);
        final Instant published = Instant.now();

        broker.publishEvent(started);

        assertDeadLetteredWithoutRetries(INGESTION_DLQ, token, published);
        assertValidFilingStillFlows();
    }

    private static void assertDeadLetteredWithoutRetries(final String queue, final String token, final Instant published) {
        final JsonNode deadLetter = DeadLetters.awaitDeadLettered(broker, queue, token);
        final Duration elapsed = Duration.between(published, Instant.now());
        assertThat(elapsed).as("time to %s (3 attempts need at least %s)", queue, Timeouts.MIN_RETRY_BACKOFF)
                .isLessThan(Timeouts.MIN_RETRY_BACKOFF);
        final JsonNode death = DeadLetters.death(deadLetter);
        assertThat(death.path("count").asInt()).isOne();
        assertThat(death.path("reason").asString()).isEqualTo("rejected");
        assertThat(death.path("queue").asString()).isEqualTo(queue.substring(0, queue.lastIndexOf('.')));
    }

    private static void assertValidFilingStillFlows() {
        final UUID filingId = api.submitAccepted(FilingRequest.noRisk("After a poison message"));
        api.awaitStatus(filingId, "COMPLETED");
        assertThat(api.awaitReport(filingId).path("status").asString()).isEqualTo("COMPLETED");
    }
}
