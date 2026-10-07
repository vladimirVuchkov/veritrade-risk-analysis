package com.veritrade.contracts.event;

import com.veritrade.contracts.messaging.MessagingTopology;

/** Every event in the system, with the routing key it is published under and its payload type. */
public enum EventType {
    FILING_SUBMITTED(MessagingTopology.RK_FILING_SUBMITTED, FilingSubmittedPayload.class),
    ANALYSIS_STARTED(MessagingTopology.RK_ANALYSIS_STARTED, AnalysisStartedPayload.class),
    ANALYSIS_COMPLETED(MessagingTopology.RK_ANALYSIS_COMPLETED, AnalysisCompletedPayload.class),
    ANALYSIS_FAILED(MessagingTopology.RK_ANALYSIS_FAILED, AnalysisFailedPayload.class);

    private final String routingKey;
    private final Class<?> payloadType;

    EventType(String routingKey, Class<?> payloadType) {
        this.routingKey = routingKey;
        this.payloadType = payloadType;
    }

    public String routingKey() {
        return routingKey;
    }

    public Class<?> payloadType() {
        return payloadType;
    }

    public static EventType fromRoutingKey(String routingKey) {
        for (EventType type : values()) {
            if (type.routingKey.equals(routingKey)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown routing key: " + routingKey);
    }
}
