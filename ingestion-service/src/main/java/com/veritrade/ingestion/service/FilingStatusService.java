package com.veritrade.ingestion.service;

import com.veritrade.ingestion.config.IngestionProperties;
import com.veritrade.ingestion.domain.Filing;
import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.domain.StatusChange;
import com.veritrade.ingestion.repository.FilingRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final int maxFailureReasonLength;

    public FilingStatusService(FilingRepository filings, Clock clock, IngestionProperties properties) {
        this.filings = filings;
        this.clock = clock;
        this.maxFailureReasonLength = properties.filing().maxFailureReasonLength();
    }

    /** Throws {@link FilingNotFoundException} for an unknown filing; never throws for a valid filing. */
    @Transactional
    public StatusChange apply(StatusUpdate update) {
        Filing filing = filings.findById(update.filingId())
                .orElseThrow(() -> new FilingNotFoundException(update.filingId()));
        FilingStatus before = filing.status();
        StatusChange change = filing.changeStatus(update.target(), shorten(update.failureReason()),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        logChange(change, update, before);
        return change;
    }

    private static void logChange(StatusChange change, StatusUpdate update, FilingStatus before) {
        switch (change) {
            case APPLIED -> log.info("Filing {} moved from {} to {} by event {}",
                    update.filingId(), before, update.target(), update.eventId());
            case DUPLICATE -> log.info("Event {} ignored: filing {} is already {}",
                    update.eventId(), update.filingId(), before);
            case REJECTED -> log.warn("Late or contradictory event {} ignored: filing {} is {}, event requests {}",
                    update.eventId(), update.filingId(), before, update.target());
        }
    }

    /** Cuts a reason longer than the column, without splitting a surrogate pair. */
    private String shorten(String reason) {
        if (reason == null || reason.length() <= maxFailureReasonLength) {
            return reason;
        }
        int end = Character.isHighSurrogate(reason.charAt(maxFailureReasonLength - 1))
                ? maxFailureReasonLength - 1
                : maxFailureReasonLength;
        return reason.substring(0, end);
    }
}
