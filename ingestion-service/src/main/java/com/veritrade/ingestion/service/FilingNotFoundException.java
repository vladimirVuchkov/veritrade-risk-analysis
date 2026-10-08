package com.veritrade.ingestion.service;

import java.util.UUID;

public class FilingNotFoundException extends RuntimeException {

    private final UUID filingId;

    public FilingNotFoundException(UUID filingId) {
        super("No filing with id " + filingId);
        this.filingId = filingId;
    }

    public UUID filingId() {
        return filingId;
    }
}
