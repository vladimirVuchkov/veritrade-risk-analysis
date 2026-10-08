package com.veritrade.reporting.messaging;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import java.util.Set;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads an analysis result message as a tolerant reader: unknown fields are ignored, the event type
 * comes from the {@code eventType} field (or, when it is absent, from the routing key) and never from
 * a Java type header. Anything unreadable or invalid raises {@link InvalidEventException}.
 */
@Component
public class AnalysisEventReader {

    private static final String EVENT_TYPE_FIELD = "eventType";
    private static final Set<EventType> SUPPORTED = Set.of(EventType.ANALYSIS_COMPLETED, EventType.ANALYSIS_FAILED);

    private final JsonMapper jsonMapper;
    private final AnalysisEventValidator validator;

    public AnalysisEventReader(final JsonMapper jsonMapper, final AnalysisEventValidator validator) {
        this.jsonMapper = jsonMapper;
        this.validator = validator;
    }

    public EventEnvelope<?> read(final Message message) {
        final ObjectNode tree = parseObject(message.getBody());
        final EventType type = eventType(tree, message.getMessageProperties().getReceivedRoutingKey());
        tree.put(EVENT_TYPE_FIELD, type.name());
        final EventEnvelope<?> envelope = toEnvelope(tree, type);
        validator.validate(envelope);
        return envelope;
    }

    private ObjectNode parseObject(final byte[] body) {
        try {
            final JsonNode tree = jsonMapper.readTree(body);
            if (tree instanceof ObjectNode object) {
                return object;
            }
        } catch (final JacksonException e) {
            throw new InvalidEventException("Message body is not valid JSON", e);
        }
        throw new InvalidEventException("Message body is not a JSON object");
    }

    private static EventType eventType(final ObjectNode tree, final String routingKey) {
        final JsonNode field = tree.get(EVENT_TYPE_FIELD);
        final EventType type = field == null || field.isNull() ? fromRoutingKey(routingKey) : fromName(field.asString());
        if (!SUPPORTED.contains(type)) {
            throw new InvalidEventException("Event type not consumed by Reporting: " + type);
        }
        return type;
    }

    private static EventType fromName(final String name) {
        try {
            return EventType.valueOf(name);
        } catch (final IllegalArgumentException e) {
            throw new InvalidEventException("Unknown eventType: " + name, e);
        }
    }

    private static EventType fromRoutingKey(final String routingKey) {
        try {
            return EventType.fromRoutingKey(routingKey);
        } catch (final IllegalArgumentException e) {
            throw new InvalidEventException("No eventType and unknown routing key: " + routingKey, e);
        }
    }

    private EventEnvelope<?> toEnvelope(final ObjectNode tree, final EventType type) {
        final JavaType envelopeType = jsonMapper.getTypeFactory()
                .constructParametricType(EventEnvelope.class, type.payloadType());
        try {
            return jsonMapper.readerFor(envelopeType)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .readValue(tree);
        } catch (final JacksonException e) {
            throw new InvalidEventException("Event does not match the " + type + " contract", e);
        }
    }
}
