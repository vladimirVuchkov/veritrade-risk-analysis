package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.e2e.support.Api;
import com.veritrade.e2e.support.ApiResponse;
import com.veritrade.e2e.support.ComposeStack;
import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.FilingRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Scenario 5: X-Correlation-Id from the HTTP request through every service and back. */
class CorrelationIdE2E extends E2ETestBase {

    private static final int MAX_CORRELATION_ID_LENGTH = 128;

    @Test
    void correlationIdIsReturnedAndLoggedByNginxAndAllThreeServices() {
        String correlationId = correlationId("correlation");

        ApiResponse response = api.submit(system.demoFiling().toJson(), Map.of(Api.CORRELATION_HEADER, correlationId));
        UUID filingId = UUID.fromString(response.json().path("filingId").asString());

        assertThat(response.header(Api.CORRELATION_HEADER)).hasValue(correlationId);
        api.awaitStatus(filingId, "COMPLETED");
        api.awaitReport(filingId);
        system.awaitLogLine(ComposeStack.FRONTEND, "POST /api/filings", correlationId);
        system.awaitLogLine(ComposeStack.INGESTION, correlationId, "Published event");
        system.awaitLogLine(ComposeStack.INGESTION, correlationId, "moved from ANALYZING to COMPLETED");
        system.awaitLogLine(ComposeStack.ANALYSIS, correlationId, "Filing " + filingId + " analysed");
        system.awaitLogLine(ComposeStack.REPORTING, correlationId, "Report stored");
    }

    @Test
    void missingCorrelationIdIsGeneratedAndReturned() {
        ApiResponse response = api.submit(FilingRequest.noRisk("No correlation id").toJson(), Map.of());

        String generated = response.header(Api.CORRELATION_HEADER).orElseThrow();
        assertThat(UUID.fromString(generated)).isNotNull();
        UUID filingId = UUID.fromString(response.json().path("filingId").asString());
        api.awaitReport(filingId);
        system.awaitLogLine(ComposeStack.REPORTING, generated, "Report stored");
    }

    @Test
    void correlationIdOverTheMaximumLengthIsReplacedByAGeneratedOne() {
        String tooLong = "x".repeat(MAX_CORRELATION_ID_LENGTH + 1);

        ApiResponse response = api.submit(FilingRequest.noRisk("Long correlation id").toJson(),
                Map.of(Api.CORRELATION_HEADER, tooLong));

        assertThat(UUID.fromString(response.header(Api.CORRELATION_HEADER).orElseThrow())).isNotNull();
    }

    @Test
    void correlationIdAtTheMaximumLengthIsKept() {
        String atLimit = "y".repeat(MAX_CORRELATION_ID_LENGTH);

        ApiResponse response = api.submit(FilingRequest.noRisk("Correlation id at the limit").toJson(),
                Map.of(Api.CORRELATION_HEADER, atLimit));

        assertThat(response.header(Api.CORRELATION_HEADER)).hasValue(atLimit);
    }

    @Test
    void correlationIdIsReturnedOnErrorsToo() {
        String correlationId = correlationId("error");

        ApiResponse response = api.submit("{not json", Map.of(Api.CORRELATION_HEADER, correlationId));

        assertThat(response.header(Api.CORRELATION_HEADER)).hasValue(correlationId);
    }
}
