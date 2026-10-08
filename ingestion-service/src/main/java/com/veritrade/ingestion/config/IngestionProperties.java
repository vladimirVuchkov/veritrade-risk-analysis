package com.veritrade.ingestion.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Every limit and interval of the service, bound from the {@code ingestion.*} configuration. */
@Validated
@ConfigurationProperties("ingestion")
public record IngestionProperties(
        @Valid @NotNull FilingLimits filing,
        @Valid @NotNull ListingLimits listing,
        @Valid @NotNull CorrelationIdLimits correlationId,
        @Valid @NotNull Outbox outbox) {

    /** Lengths are in UTF-16 units (as the database columns count them); content is measured in UTF-8 bytes. */
    public record FilingLimits(
            @Positive int maxContentBytes,
            @Positive int maxCompanyNameLength,
            @Positive int maxTitleLength,
            @Positive int maxFailureReasonLength) {
    }

    public record ListingLimits(@Positive int defaultLimit, @Positive int maxLimit) {
    }

    public record CorrelationIdLimits(@Positive int maxLength) {
    }

    /**
     * The publisher sends at most {@code batchSize} rows per query and waits {@code confirmTimeout} per confirm.
     * After a failed run it waits {@code retryBackoff}, multiplied by {@code retryBackoffMultiplier} after each
     * further failed run up to {@code maxRetryBackoff}. A row that fails {@code maxAttempts} times on its own
     * (not because of the broker) is parked.
     */
    public record Outbox(
            boolean enabled,
            @NotNull Duration publishInterval,
            @Positive int batchSize,
            @NotNull Duration confirmTimeout,
            @Positive int maxAttempts,
            @NotNull Duration retryBackoff,
            @DecimalMin("1.0") double retryBackoffMultiplier,
            @NotNull Duration maxRetryBackoff) {
    }
}
