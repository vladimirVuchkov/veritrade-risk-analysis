package com.veritrade.e2e.support;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Contract events built by hand, for the scenarios that play the part of a producer. */
public final class Events {

    private Events() {
    }

    public static ObjectNode filingSubmitted(final UUID eventId, final UUID filingId, final String correlationId, final FilingRequest filing) {
        final ObjectNode payload = Json.object();
        payload.put("filingId", filingId.toString());
        payload.put("companyName", filing.companyName());
        payload.put("title", filing.title());
        payload.put("content", filing.content());
        payload.put("submittedAt", Instant.now().toString());
        return envelope(eventId, EventType.FILING_SUBMITTED, correlationId, payload);
    }

    public static ObjectNode started(final UUID eventId, final UUID filingId, final String correlationId) {
        final ObjectNode payload = Json.object();
        payload.put("filingId", filingId.toString());
        payload.put("startedAt", Instant.now().toString());
        return envelope(eventId, EventType.ANALYSIS_STARTED, correlationId, payload);
    }

    /** analysis.completed whose summary is computed from the findings (overall level = highest severity). */
    public static ObjectNode completed(final UUID eventId, final UUID filingId, final String correlationId, final List<ObjectNode> findings) {
        final ObjectNode payload = Json.object();
        payload.put("filingId", filingId.toString());
        payload.put("analyzedAt", Instant.now().toString());
        payload.put("rulesVersion", "e2e");
        payload.set("summary", summary(findings));
        payload.putArray("findings").addAll(findings);
        return envelope(eventId, EventType.ANALYSIS_COMPLETED, correlationId, payload);
    }

    public static ObjectNode failed(final UUID eventId, final UUID filingId, final String correlationId, final String reason) {
        final ObjectNode payload = Json.object();
        payload.put("filingId", filingId.toString());
        payload.put("failedAt", Instant.now().toString());
        payload.put("reason", reason);
        return envelope(eventId, EventType.ANALYSIS_FAILED, correlationId, payload);
    }

    public static ObjectNode finding(final RiskCategory category, final Severity severity, final String ruleId, final String matchedText, final int position) {
        final ObjectNode finding = Json.object();
        finding.put("category", category.name());
        finding.put("severity", severity.name());
        finding.put("ruleId", ruleId);
        finding.put("matchedText", matchedText);
        finding.put("excerpt", "... " + matchedText + " ...");
        finding.put("position", position);
        return finding;
    }

    public static EventType typeOf(final JsonNode event) {
        return EventType.valueOf(event.path("eventType").asString());
    }

    private static ObjectNode envelope(final UUID eventId, final EventType type, final String correlationId, final ObjectNode payload) {
        final ObjectNode event = Json.object();
        event.put("eventId", eventId.toString());
        event.put("eventType", type.name());
        event.put("eventVersion", EventEnvelope.CURRENT_VERSION);
        event.put("occurredAt", Instant.now().toString());
        event.put("correlationId", correlationId);
        event.set("payload", payload);
        return event;
    }

    private static ObjectNode summary(final List<ObjectNode> findings) {
        final ObjectNode summary = Json.object();
        summary.put("totalFindings", findings.size());
        summary.put("overallRiskLevel", findings.stream()
                .map(finding -> Severity.valueOf(finding.path("severity").asString()))
                .max(Enum::compareTo)
                .map(severity -> RiskLevel.valueOf(severity.name()))
                .orElse(RiskLevel.NONE)
                .name());
        final ObjectNode byCategory = summary.putObject("byCategory");
        findings.forEach(finding -> {
            final String category = finding.path("category").asString();
            byCategory.put(category, byCategory.path(category).asInt() + 1);
        });
        return summary;
    }
}
