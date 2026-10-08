package com.veritrade.reporting.support;

import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.AnalysisFailedPayload;
import com.veritrade.contracts.event.AnalysisSummary;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.event.FindingPayload;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import com.veritrade.reporting.config.ReportingLimits;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Builders for analysis events, contract examples and AMQP messages used across the tests. */
public final class TestEvents {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public static final ReportingLimits LIMITS = new ReportingLimits(32, 32, 500, 1000, 1000);

    public static final String COMPLETED_EXAMPLE = "contracts/examples/analysis-completed.json";
    public static final String FAILED_EXAMPLE = "contracts/examples/analysis-failed.json";

    private static final Instant AT = Instant.parse("2026-10-07T12:00:02Z");
    private static final String CORRELATION_ID = "test-correlation-id";

    private TestEvents() {
    }

    public static FindingPayload finding(final RiskCategory category, final Severity severity, final int position) {
        return new FindingPayload(category, severity, "LEGAL-001", "pending litigation",
                "We are subject to pending litigation.", position);
    }

    public static AnalysisCompletedPayload completed(final UUID filingId, final RiskLevel level, final List<FindingPayload> findings) {
        final Map<RiskCategory, Integer> byCategory = new EnumMap<>(RiskCategory.class);
        findings.forEach(f -> byCategory.merge(f.category(), 1, Integer::sum));
        return new AnalysisCompletedPayload(
                filingId, AT, "1.0", new AnalysisSummary(findings.size(), level, byCategory), findings);
    }

    public static AnalysisFailedPayload failed(final UUID filingId, final String reason) {
        return new AnalysisFailedPayload(filingId, AT, reason);
    }

    public static EventEnvelope<AnalysisCompletedPayload> completedEnvelope(final AnalysisCompletedPayload payload) {
        return EventEnvelope.of(EventIds.forFiling(payload.filingId(), EventType.ANALYSIS_COMPLETED),
                EventType.ANALYSIS_COMPLETED, AT, CORRELATION_ID, payload);
    }

    public static EventEnvelope<AnalysisFailedPayload> failedEnvelope(final AnalysisFailedPayload payload) {
        return EventEnvelope.of(EventIds.forFiling(payload.filingId(), EventType.ANALYSIS_FAILED),
                EventType.ANALYSIS_FAILED, AT, CORRELATION_ID, payload);
    }

    public static ObjectNode tree(final EventEnvelope<?> envelope) {
        return MAPPER.valueToTree(envelope);
    }

    public static ObjectNode example(final String path) {
        try (InputStream in = TestEvents.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + path);
            }
            return (ObjectNode) MAPPER.readTree(in);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Message message(final Object body, final String routingKey) {
        final String json = body instanceof String text ? text : MAPPER.writeValueAsString(body);
        return message(json.getBytes(StandardCharsets.UTF_8), routingKey);
    }

    public static Message message(final byte[] body, final String routingKey) {
        final MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setReceivedRoutingKey(routingKey);
        properties.setCorrelationId(CORRELATION_ID);
        return MessageBuilder.withBody(body).andProperties(properties).build();
    }

    /** A string of the given number of code points, each a supplementary character (two UTF-16 units). */
    public static String emoji(final int codePoints) {
        return "😀".repeat(codePoints);
    }
}
