package com.veritrade.ingestion.api.dto;

import com.veritrade.ingestion.service.FilingSubmission;

public record SubmitFilingRequest(String companyName, String title, String content) {

    public FilingSubmission toSubmission() {
        return new FilingSubmission(companyName, title, content);
    }
}
