package com.veritrade.ingestion.service;

/** A filing as submitted by a client, before validation. Any field may be null. */
public record FilingSubmission(String companyName, String title, String content) {
}
