package com.veritrade.ingestion.messaging;

import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.AnalysisFailedPayload;
import com.veritrade.contracts.event.AnalysisStartedPayload;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.ingestion.config.IngestionProperties;
import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.service.StatusUpdate;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tolerant reader of analysis events: dispatches on the {@code eventType} field (never on a Java type
 * header), ignores unknown fields and rejects with {@link InvalidEventException} anything it cannot use.
 * As the contract requires (messaging-topology.md, "Event versioning" and "Text limits"), that includes an
 * {@code eventVersion} above {@link EventEnvelope#CURRENT_VERSION} and a failure reason over its limit in
 * UTF-16 units: both go to the dead-letter queue without retries, so they can be replayed later.
 */
@Component
public class AnalysisEventReader {

    private final JsonMapper jsonMapper;
    private final int maxFailureReasonLength;

    public AnalysisEventReader(JsonMapper jsonMapper, IngestionProperties properties) {
        this.jsonMapper = jsonMapper;
        this.maxFailureReasonLength = properties.filing().maxFailureReasonLength();
    }

    public AnalysisEvent read(byte[] body) {
        JsonNode tree = parse(body);
        EventType type = analysisEventType(tree);
        requireSupportedVersion(tree);
        EventEnvelope<?> envelope = envelope(tree, type);
        return new AnalysisEvent(type, envelope.correlationId(), statusUpdate(envelope));
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode tree = jsonMapper.readTree(body);
            if (tree == null || !tree.isObject()) {
                throw new InvalidEventException("Message body is not a JSON object");
            }
            return tree;
        } catch (JacksonException e) {
            throw new InvalidEventException("Message body is not valid JSON", e);
        }
    }

    private static EventType analysisEventType(JsonNode tree) {
        JsonNode node = tree.get("eventType");
        if (node == null || !node.isString()) {
            throw new InvalidEventException("eventType is missing");
        }
        return Arrays.stream(EventType.values())
                .filter(type -> type.name().equals(node.asString()) && type != EventType.FILING_SUBMITTED)
                .findFirst()
                .orElseThrow(() -> new InvalidEventException("Not an analysis event type: " + node.asString()));
    }

    /** A missing or malformed version is left to the envelope check; only a newer one is singled out here. */
    private static void requireSupportedVersion(JsonNode tree) {
        JsonNode version = tree.get("eventVersion");
        if (version != null && version.isIntegralNumber()
                && version.bigIntegerValue().compareTo(BigInteger.valueOf(EventEnvelope.CURRENT_VERSION)) > 0) {
            throw new InvalidEventException("Unsupported eventVersion " + version
                    + "; the highest supported version is " + EventEnvelope.CURRENT_VERSION);
        }
    }

    private EventEnvelope<?> envelope(JsonNode tree, EventType type) {
        JavaType javaType = jsonMapper.getTypeFactory().constructParametricType(EventEnvelope.class, type.payloadType());
        try {
            return jsonMapper.readerFor(javaType)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(tree);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new InvalidEventException("Invalid " + type + " event: " + e.getMessage(), e);
        }
    }

    private StatusUpdate statusUpdate(EventEnvelope<?> envelope) {
        return switch (envelope.payload()) {
            case AnalysisStartedPayload started ->
                    update(envelope, started.filingId(), FilingStatus.ANALYZING, null);
            case AnalysisCompletedPayload completed ->
                    update(envelope, completed.filingId(), FilingStatus.COMPLETED, null);
            case AnalysisFailedPayload failed ->
                    update(envelope, failed.filingId(), FilingStatus.FAILED, requireReason(failed.reason()));
            default -> throw new InvalidEventException("Unexpected payload " + envelope.payload().getClass());
        };
    }

    private static StatusUpdate update(EventEnvelope<?> envelope, UUID filingId, FilingStatus target, String reason) {
        if (filingId == null) {
            throw new InvalidEventException("payload.filingId is missing in event " + envelope.eventId());
        }
        return new StatusUpdate(envelope.eventId(), filingId, target, reason);
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidEventException("payload.reason is missing");
        }
        if (reason.length() > maxFailureReasonLength) {
            throw new InvalidEventException("payload.reason has " + reason.length()
                    + " UTF-16 units; the limit is " + maxFailureReasonLength);
        }
        return reason;
    }
}
