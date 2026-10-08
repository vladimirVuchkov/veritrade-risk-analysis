package com.veritrade.ingestion.service;

import com.veritrade.ingestion.domain.FilingState;
import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.domain.StatusChange;
import com.veritrade.ingestion.repository.FilingRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies analysis events to the filing status. Redelivered events are no-ops. Late or contradictory
 * events (anything after a final status, or a step backwards) are ignored with a WARN line and never
 * throw, because a throw would retry and then dead-letter a valid message.
 */
@Service
public class FilingStatusService {

    private static final Logger log = LoggerFactory.getLogger(FilingStatusService.class);

    private final FilingRepository filings;
    private final Clock clock;

    public FilingStatusService(final FilingRepository filings, final Clock clock) {
        this.filings = filings;
        this.clock = clock;
    }

    /**
     * Throws {@link FilingNotFoundException} for an unknown filing, and {@link OptimisticLockingFailureException}
     * when the filing changed between the read and the write (the listener retry then reads it again).
     * Neither the read nor the write loads the filing content.
     */
    @Transactional
    public StatusChange apply(final StatusUpdate update) {
        final FilingState state = filings.findStateById(update.filingId())
                .orElseThrow(() -> new FilingNotFoundException(update.filingId()));
        final StatusChange change = state.status().transitionTo(update.target());
        if (change == StatusChange.APPLIED) {
            store(state, update);
        }
        logChange(change, update, state.status());
        return change;
    }

    private void store(final FilingState state, final StatusUpdate update) {
        final int changed = filings.changeStatus(state.id(), state.version(), update.target(),
                update.target().failureReasonToKeep(update.failureReason()),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        if (changed == 0) {
            throw new OptimisticLockingFailureException("Filing " + state.id() + " changed concurrently");
        }
    }

    private static void logChange(final StatusChange change, final StatusUpdate update, final FilingStatus before) {
        switch (change) {
            case APPLIED -> log.info("Filing {} moved from {} to {} by event {}",
                    update.filingId(), before, update.target(), update.eventId());
            case DUPLICATE -> log.info("Event {} ignored: filing {} is already {}",
                    update.eventId(), update.filingId(), before);
            case REJECTED -> log.warn("Late or contradictory event {} ignored: filing {} is {}, event requests {}",
                    update.eventId(), update.filingId(), before, update.target());
        }
    }
}
