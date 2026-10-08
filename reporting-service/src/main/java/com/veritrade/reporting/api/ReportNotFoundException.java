package com.veritrade.reporting.api;

import java.util.UUID;

public class ReportNotFoundException extends RuntimeException {

    private ReportNotFoundException(final String message) {
        super(message);
    }

    static ReportNotFoundException notReady(final UUID filingId) {
        return new ReportNotFoundException("No report for filing " + filingId + " yet");
    }

    static ReportNotFoundException malformedId() {
        return new ReportNotFoundException("Filing id is not a valid UUID");
    }
}
