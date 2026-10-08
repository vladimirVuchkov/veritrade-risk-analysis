package com.veritrade.ingestion.support;

import com.veritrade.ingestion.config.IngestionProperties;
import java.time.Duration;

/** The values of application.yml, for unit tests that build the services by hand. */
public final class TestProperties {

    public static final int MAX_CONTENT_BYTES = 2_097_152;
    public static final int MAX_COMPANY_NAME_LENGTH = 200;
    public static final int MAX_TITLE_LENGTH = 300;
    public static final int MAX_FAILURE_REASON_LENGTH = 1000;
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_CORRELATION_ID_LENGTH = 128;
    public static final int MAX_ATTEMPTS = 3;
    public static final double BACKOFF_MULTIPLIER = 2;

    private TestProperties() {
    }

    public static IngestionProperties defaults() {
        return withOutbox(3, Duration.ofMillis(200));
    }

    /** Without a back-off between runs, so every call of the publisher runs. */
    public static IngestionProperties withOutbox(int batchSize, Duration confirmTimeout) {
        return withOutbox(batchSize, confirmTimeout, Duration.ZERO, Duration.ZERO);
    }

    public static IngestionProperties withOutbox(int batchSize, Duration confirmTimeout, Duration retryBackoff,
            Duration maxRetryBackoff) {
        return new IngestionProperties(
                new IngestionProperties.FilingLimits(MAX_CONTENT_BYTES, MAX_COMPANY_NAME_LENGTH, MAX_TITLE_LENGTH,
                        MAX_FAILURE_REASON_LENGTH),
                new IngestionProperties.ListingLimits(DEFAULT_LIMIT, MAX_LIMIT),
                new IngestionProperties.CorrelationIdLimits(MAX_CORRELATION_ID_LENGTH),
                new IngestionProperties.Outbox(true, Duration.ofMillis(500), batchSize, confirmTimeout, MAX_ATTEMPTS,
                        retryBackoff, BACKOFF_MULTIPLIER, maxRetryBackoff));
    }
}
