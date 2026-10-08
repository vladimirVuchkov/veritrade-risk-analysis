package com.veritrade.contracts.messaging;

import com.veritrade.contracts.event.EventType;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic event ids. Each filing produces at most one event of each type, so a name-based
 * UUID of (filingId, eventType) gives a redelivered or recomputed event the same id, which
 * consumers use for deduplication.
 */
public final class EventIds {

    private EventIds() {
    }

    public static UUID forFiling(final UUID filingId, final EventType eventType) {
        final String name = filingId + ":" + eventType.name();
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
