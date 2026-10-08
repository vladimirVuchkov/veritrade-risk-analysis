package com.veritrade.contracts.messaging;

/**
 * Names of every exchange, routing key and queue. The exact declarations and arguments are
 * specified in docs/contracts/messaging-topology.md; each service declares what it uses.
 */
public final class MessagingTopology {

    public static final String EVENTS_EXCHANGE = "veritrade.events";
    public static final String DEAD_LETTER_EXCHANGE = "veritrade.dlx";

    public static final String RK_FILING_SUBMITTED = "filing.submitted";
    public static final String RK_ANALYSIS_STARTED = "analysis.started";
    public static final String RK_ANALYSIS_COMPLETED = "analysis.completed";
    public static final String RK_ANALYSIS_FAILED = "analysis.failed";
    public static final String RK_ANALYSIS_ALL = "analysis.*";

    public static final String Q_ANALYSIS_FILING_SUBMITTED = "analysis.filing-submitted";
    public static final String Q_INGESTION_ANALYSIS_EVENTS = "ingestion.analysis-events";
    public static final String Q_REPORTING_ANALYSIS_RESULTS = "reporting.analysis-results";

    public static final String DLQ_SUFFIX = ".dlq";

    public static final String ARG_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    public static final String ARG_DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";

    private MessagingTopology() {
    }

    /** Dead-letter queue of a work queue; it is bound to the dead-letter exchange with its work queue name as key. */
    public static String deadLetterQueue(final String workQueue) {
        return workQueue + DLQ_SUFFIX;
    }
}
