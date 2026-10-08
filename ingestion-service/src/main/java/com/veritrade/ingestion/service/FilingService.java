package com.veritrade.ingestion.service;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.event.FilingSubmittedPayload;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.ingestion.config.IngestionProperties;
import com.veritrade.ingestion.domain.Filing;
import com.veritrade.ingestion.domain.FilingView;
import com.veritrade.ingestion.repository.FilingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Accepts filings and answers status queries. */
@Service
public class FilingService {

    private static final Logger log = LoggerFactory.getLogger(FilingService.class);

    private final FilingRepository filings;
    private final OutboxService outbox;
    private final FilingValidator validator;
    private final Clock clock;
    private final IngestionProperties.ListingLimits listing;

    public FilingService(final FilingRepository filings, final OutboxService outbox, final FilingValidator validator, final Clock clock,
            final IngestionProperties properties) {
        this.filings = filings;
        this.outbox = outbox;
        this.validator = validator;
        this.clock = clock;
        this.listing = properties.listing();
    }

    /** Stores the filing and its {@code filing.submitted} event in one transaction. */
    @Transactional
    public FilingView submit(final FilingSubmission submission, final String correlationId) {
        Objects.requireNonNull(correlationId, "correlationId");
        final FilingSubmission valid = validator.validate(submission);
        final Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        final Filing filing = filings.save(
                Filing.submit(UUID.randomUUID(), valid.companyName(), valid.title(), valid.content(), now));
        outbox.enqueue(submittedEvent(filing, correlationId));
        log.info("Filing {} submitted", filing.id());
        return FilingView.of(filing);
    }

    @Transactional(readOnly = true)
    public FilingView get(final UUID filingId) {
        return filings.findViewById(filingId).orElseThrow(() -> new FilingNotFoundException(filingId));
    }

    /** Newest first. A null limit means the default; otherwise it must be between 1 and the maximum. */
    @Transactional(readOnly = true)
    public List<FilingView> listRecent(final Integer limit) {
        final int effective = limit == null ? listing.defaultLimit() : limit;
        if (effective < 1 || effective > listing.maxLimit()) {
            throw new InvalidRequestException(List.of("limit must be between 1 and " + listing.maxLimit()));
        }
        return filings.findAllByOrderBySubmittedAtDescIdDesc(Limit.of(effective));
    }

    private static EventEnvelope<FilingSubmittedPayload> submittedEvent(final Filing filing, final String correlationId) {
        final FilingSubmittedPayload payload = new FilingSubmittedPayload(
                filing.id(), filing.companyName(), filing.title(), filing.content(), filing.submittedAt());
        return EventEnvelope.of(EventIds.forFiling(filing.id(), EventType.FILING_SUBMITTED),
                EventType.FILING_SUBMITTED, filing.submittedAt(), correlationId, payload);
    }
}
