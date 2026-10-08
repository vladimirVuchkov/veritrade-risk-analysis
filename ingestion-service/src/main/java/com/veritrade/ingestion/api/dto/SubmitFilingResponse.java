package com.veritrade.ingestion.api.dto;

import com.veritrade.ingestion.domain.FilingStatus;
import java.util.UUID;

public record SubmitFilingResponse(UUID filingId, FilingStatus status) {
}
