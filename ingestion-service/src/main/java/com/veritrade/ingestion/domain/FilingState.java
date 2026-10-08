package com.veritrade.ingestion.domain;

import java.util.UUID;

/** Status and optimistic-lock version of a filing, read without its content. */
public record FilingState(UUID id, FilingStatus status, Long version) {
}
