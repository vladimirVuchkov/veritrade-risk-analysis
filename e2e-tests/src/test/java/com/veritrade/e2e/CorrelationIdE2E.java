package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.e2e.support.Api;
import com.veritrade.e2e.support.ApiResponse;
import com.veritrade.e2e.support.ComposeStack;
import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.FilingRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Scenario 5: X-Correlation-Id from the HTTP request through every service and back. */
class CorrelationIdE2E extends E2ETestBase {

    private static final int MAX_CORRELATION_ID_LENGTH = 128;

    @Test
    void correlationIdIsReturnedAndLoggedByNginxAndAllThreeServices() {
        final String correlationId = correlationId("correlation");

        final ApiResponse response = api.submit(system.demoFiling().toJson(), Map.of(Api.CORRELATION_HEADER, correlationId));
        final UUID filingId = UUID.fromString(response.json().path("filingId").asString());

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
        final ApiResponse response = api.submit(FilingRequest.noRisk("No correlation id").toJson(), Map.of());

        final String generated = response.header(Api.CORRELATION_HEADER).orElseThrow();
        assertThat(UUID.fromString(generated)).isNotNull();
        final UUID filingId = UUID.fromString(response.json().path("filingId").asString());
        api.awaitReport(filingId);
        system.awaitLogLine(ComposeStack.REPORTING, generated, "Report stored");
    }

    @Test
    void correlationIdOverTheMaximumLengthIsReplacedByAGeneratedOne() {
        final String tooLong = "x".repeat(MAX_CORRELATION_ID_LENGTH + 1);

        final ApiResponse response = api.submit(FilingRequest.noRisk("Long correlation id").toJson(),
                Map.of(Api.CORRELATION_HEADER, tooLong));

        assertThat(UUID.fromString(response.header(Api.CORRELATION_HEADER).orElseThrow())).isNotNull();
    }

    @Test
    void correlationIdAtTheMaximumLengthIsKept() {
        final String atLimit = "y".repeat(MAX_CORRELATION_ID_LENGTH);

        final ApiResponse response = api.submit(FilingRequest.noRisk("Correlation id at the limit").toJson(),
                Map.of(Api.CORRELATION_HEADER, atLimit));

        assertThat(response.header(Api.CORRELATION_HEADER)).hasValue(atLimit);
    }

    /**
     * Review W3-01: 64 "é" sent as raw UTF-8 are 128 characters for Tomcat but 256 bytes in UTF-8, over the
     * AMQP short-string limit. Before the fix the outbox retried that row forever and every later filing stayed
     * SUBMITTED. Now the id is replaced, and this filing and the next one both complete.
     */
    @Test
    void nonAsciiCorrelationIdIsReplacedAndDoesNotBlockLaterFilings() {
        final String rawUtf8 = new String("\u00e9".repeat(64).getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);

        final ApiResponse response = api.submit(FilingRequest.noRisk("Non-ASCII correlation id").toJson(),
                Map.of(Api.CORRELATION_HEADER, rawUtf8));
        final UUID next = api.submitAccepted(FilingRequest.noRisk("Filing after a non-ASCII correlation id"));

        final String generated = response.header(Api.CORRELATION_HEADER).orElseThrow();
        assertThat(UUID.fromString(generated)).isNotNull();
        final UUID filingId = UUID.fromString(response.json().path("filingId").asString());
        api.awaitStatus(filingId, "COMPLETED");
        api.awaitStatus(next, "COMPLETED");
        system.awaitLogLine(ComposeStack.INGESTION, generated, "Published event");
    }

    @ParameterizedTest
    @ValueSource(strings = {"has inner spaces", "slash/and;semicolon", "quote\"d"})
    void correlationIdThatIsNotAStrictTokenIsReplacedByAGeneratedOne(final String header) {
        final ApiResponse response = api.submit(FilingRequest.noRisk("Correlation id not a token").toJson(),
                Map.of(Api.CORRELATION_HEADER, header));

        assertThat(UUID.fromString(response.header(Api.CORRELATION_HEADER).orElseThrow())).isNotNull();
    }

    @Test
    void correlationIdIsReturnedOnErrorsToo() {
        final String correlationId = correlationId("error");

        final ApiResponse response = api.submit("{not json", Map.of(Api.CORRELATION_HEADER, correlationId));

        assertThat(response.header(Api.CORRELATION_HEADER)).hasValue(correlationId);
    }
}
