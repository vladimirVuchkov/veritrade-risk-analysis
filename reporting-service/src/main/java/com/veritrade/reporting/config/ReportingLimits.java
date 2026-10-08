package com.veritrade.reporting.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Text limits of the analysis events. They equal the contract maxLength values (counted in code
 * points) and the column sizes of V1__init.sql (counted in UTF-16 units).
 */
@Validated
@ConfigurationProperties("reporting.limits")
public record ReportingLimits(
        @Positive int ruleIdMaxLength,
        @Positive int rulesVersionMaxLength,
        @Positive int matchedTextMaxLength,
        @Positive int excerptMaxLength,
        @Positive int failureReasonMaxLength) {
}
