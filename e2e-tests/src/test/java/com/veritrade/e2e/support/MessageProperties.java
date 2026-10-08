package com.veritrade.e2e.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

/** AMQP properties of a message published through the management API. */
public record MessageProperties(String messageId, String correlationId, Map<String, String> headers) {

    public static final String TYPE_ID_HEADER = "__TypeId__";
    private static final String JSON = "application/json";

    /** The properties a contract producer sets: messageId, correlationId, JSON, persistent. */
    public static MessageProperties contract(final String messageId, final String correlationId) {
        return new MessageProperties(messageId, correlationId, Map.of());
    }

    public MessageProperties withHeader(final String name, final String value) {
        final Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new MessageProperties(messageId, correlationId, copy);
    }

    ObjectNode toJson(final int deliveryMode) {
        final ObjectNode properties = Json.object();
        properties.put("message_id", messageId);
        properties.put("correlation_id", correlationId);
        properties.put("content_type", JSON);
        properties.put("delivery_mode", deliveryMode);
        properties.put("timestamp", Instant.now().getEpochSecond());
        final ObjectNode headerNode = properties.putObject("headers");
        headers.forEach(headerNode::put);
        return properties;
    }
}
