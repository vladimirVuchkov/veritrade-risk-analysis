package com.veritrade.e2e.support;

import static com.veritrade.contracts.messaging.MessagingTopology.Q_ANALYSIS_FILING_SUBMITTED;
import static com.veritrade.contracts.messaging.MessagingTopology.Q_INGESTION_ANALYSIS_EVENTS;
import static com.veritrade.contracts.messaging.MessagingTopology.Q_REPORTING_ANALYSIS_RESULTS;
import static com.veritrade.contracts.messaging.MessagingTopology.deadLetterQueue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Checks on the three dead-letter queues, which nothing consumes. */
public final class DeadLetters {

    public static final String ANALYSIS_DLQ = deadLetterQueue(Q_ANALYSIS_FILING_SUBMITTED);
    public static final String INGESTION_DLQ = deadLetterQueue(Q_INGESTION_ANALYSIS_EVENTS);
    public static final String REPORTING_DLQ = deadLetterQueue(Q_REPORTING_ANALYSIS_RESULTS);
    public static final List<String> ALL = List.of(ANALYSIS_DLQ, INGESTION_DLQ, REPORTING_DLQ);

    private DeadLetters() {
    }

    /** No dead-letter queue holds a message that mentions the token (a filing id or an event id). */
    public static void assertNothingDeadLetteredFor(final Broker broker, final String token) {
        ALL.forEach(queue -> assertThat(broker.peekMatching(queue, token)).as("%s mentions %s", queue, token).isEmpty());
    }

    /** Waits until the dead-letter queue holds exactly one message with the token and returns it. */
    public static JsonNode awaitDeadLettered(final Broker broker, final String queue, final String token) {
        final List<JsonNode> matching = await(queue + " holds " + token).atMost(Timeouts.MESSAGE_HANDLED)
                .pollInterval(Timeouts.POLL_INTERVAL)
                .until(() -> broker.peekMatching(queue, token), messages -> !messages.isEmpty());
        assertThat(matching).as("copies of %s in %s", token, queue).hasSize(1);
        return matching.getFirst();
    }

    /** The x-death entry that RabbitMQ adds when a message is dead-lettered. */
    public static JsonNode death(final JsonNode deadLetter) {
        return deadLetter.path("properties").path("headers").path("x-death").path(0);
    }
}
