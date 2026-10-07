package com.veritrade.contracts.logging;

/**
 * Correlation id conventions. The id enters at the REST edge (header or generated), travels in the
 * event envelope and the AMQP correlationId property, and is put in the logging context under
 * {@link #MDC_KEY} by every consumer.
 */
public final class CorrelationIds {

    public static final String HTTP_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationIds() {
    }
}
