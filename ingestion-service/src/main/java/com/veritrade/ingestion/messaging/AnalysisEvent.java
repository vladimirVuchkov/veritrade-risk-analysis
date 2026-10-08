package com.veritrade.ingestion.messaging;

import com.veritrade.contracts.event.EventType;
import com.veritrade.ingestion.service.StatusUpdate;

/** A validated analysis event, reduced to what Ingestion needs. */
public record AnalysisEvent(EventType eventType, String correlationId, StatusUpdate statusUpdate) {
}
