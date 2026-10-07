package com.veritrade.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.contracts.messaging.MessagingTopology;
import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Keeps the Java enums and constants in line with the JSON schemas and the topology. */
class ContractConsistencyTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void enumsMatchTheSchemaDefinitions() {
        JsonNode envelope = readSchema("event-envelope.schema.json");
        JsonNode defs = envelope.get("$defs");

        assertThat(enumValues(envelope.get("properties").get("eventType"))).isEqualTo(names(EventType.values()));
        assertThat(enumValues(defs.get("riskCategory"))).isEqualTo(names(RiskCategory.values()));
        assertThat(enumValues(defs.get("severity"))).isEqualTo(names(Severity.values()));
        assertThat(enumValues(defs.get("riskLevel"))).isEqualTo(names(RiskLevel.values()));
    }

    @Test
    void routingKeysResolveToTheirEventTypes() {
        for (EventType type : EventType.values()) {
            assertThat(EventType.fromRoutingKey(type.routingKey())).isEqualTo(type);
        }
        assertThat(EventType.ANALYSIS_STARTED.routingKey()).isEqualTo(MessagingTopology.RK_ANALYSIS_STARTED);
    }

    @Test
    void analysisWildcardMatchesEveryAnalysisEvent() {
        String prefix = MessagingTopology.RK_ANALYSIS_ALL.replace("*", "");

        assertThat(Arrays.stream(EventType.values()).filter(t -> t.routingKey().startsWith(prefix)))
                .containsExactly(EventType.ANALYSIS_STARTED, EventType.ANALYSIS_COMPLETED, EventType.ANALYSIS_FAILED);
    }

    @Test
    void deadLetterQueueNameFollowsTheConvention() {
        assertThat(MessagingTopology.deadLetterQueue(MessagingTopology.Q_ANALYSIS_FILING_SUBMITTED))
                .isEqualTo("analysis.filing-submitted.dlq");
    }

    @Test
    void eventIdsAreDeterministicAndDistinctPerEventType() {
        UUID filingId = UUID.randomUUID();

        assertThat(EventIds.forFiling(filingId, EventType.ANALYSIS_COMPLETED))
                .isEqualTo(EventIds.forFiling(filingId, EventType.ANALYSIS_COMPLETED))
                .isNotEqualTo(EventIds.forFiling(filingId, EventType.ANALYSIS_FAILED));
    }

    private static JsonNode readSchema(String file) {
        try (InputStream in = ContractExamplesTest.resource("contracts/" + file)) {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> enumValues(JsonNode node) {
        return node.get("enum").valueStream().map(JsonNode::asString).toList();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
