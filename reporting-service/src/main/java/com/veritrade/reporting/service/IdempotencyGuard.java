package com.veritrade.reporting.service;

import com.veritrade.reporting.domain.ProcessedEvent;
import com.veritrade.reporting.repository.ProcessedEventRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "have I seen this eventId?". The processed marker joins the caller's transaction, so it is
 * committed or rolled back together with the report it produced.
 */
@Component
public class IdempotencyGuard {

    private final ProcessedEventRepository processedEvents;
    private final Clock clock;

    public IdempotencyGuard(ProcessedEventRepository processedEvents, Clock clock) {
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean alreadyProcessed(UUID eventId) {
        return processedEvents.existsById(eventId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markProcessed(UUID eventId) {
        processedEvents.save(new ProcessedEvent(eventId, clock.instant()));
    }
}
