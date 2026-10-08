package com.veritrade.analysis.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the event publisher.
 *
 * @param confirmTimeout  how long to wait for the broker's publisher confirm before the publish counts as failed
 * @param maxReasonLength the {@code analysis.failed} reason is cut to this many UTF-16 code units, without
 *                        splitting a surrogate pair; at most {@value #SCHEMA_MAX_REASON_LENGTH}, the schema limit
 */
@ConfigurationProperties("veritrade.analysis.messaging")
public record MessagingProperties(
        @DefaultValue("5s") Duration confirmTimeout,
        @DefaultValue("1000") int maxReasonLength) {

    /** {@code maxLength} of {@code reason} in analysis-failed.schema.json. */
    public static final int SCHEMA_MAX_REASON_LENGTH = 1000;
    /** Room for one surrogate pair, so that cutting never leaves an empty reason. */
    private static final int MIN_REASON_LENGTH = 2;

    public MessagingProperties {
        if (maxReasonLength < MIN_REASON_LENGTH || maxReasonLength > SCHEMA_MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("maxReasonLength must be between " + MIN_REASON_LENGTH + " and "
                    + SCHEMA_MAX_REASON_LENGTH + ", was " + maxReasonLength);
        }
    }
}
