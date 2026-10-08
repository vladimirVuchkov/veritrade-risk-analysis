package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.e2e.support.ApiResponse;
import com.veritrade.e2e.support.ComposeStack;
import com.veritrade.e2e.support.Contracts;
import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.FilingRequest;
import com.veritrade.e2e.support.ReportAssertions;
import com.veritrade.e2e.support.Timeouts;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Scenarios 1 and 2: a filing goes all the way through the real system. */
class HappyPathE2E extends E2ETestBase {

    private static final int ACCEPTED = 202;

    @Test
    void sampleFilingIsAnalysedAndReportedWithinTenSeconds() {
        final FilingRequest filing = system.demoFiling();
        final long started = System.nanoTime();
        final ApiResponse submitted = api.submit(filing.toJson(), Map.of());
        final UUID filingId = acceptedAsSubmitted(submitted);

        final JsonNode report = api.awaitReport(filingId, Timeouts.REPORT_DEADLINE);
        final Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(Timeouts.REPORT_DEADLINE);
        final JsonNode status = api.awaitStatus(filingId, "COMPLETED", Timeouts.REPORT_DEADLINE);
        Contracts.assertMatchesApiSchema("FilingStatusResponse", status);
        assertThat(status.path("companyName").asString()).isEqualTo(filing.companyName());
        assertThat(status.path("failureReason").isMissingNode() || status.path("failureReason").isNull()).isTrue();
        ReportAssertions.assertConsistentCompletedReport(report, filing.content());
        assertThat(report.path("summary").path("totalFindings").asInt()).isPositive();
        assertThat(report.path("filingId").asString()).isEqualTo(filingId.toString());
        assertWentThroughAnalyzing(filingId);
    }

    @Test
    void filingWithoutRiskTextCompletesWithNoFindings() {
        final UUID filingId = acceptedAsSubmitted(api.submit(FilingRequest.noRisk("No risk at all").toJson(), Map.of()));

        api.awaitStatus(filingId, "COMPLETED");
        final JsonNode report = api.awaitReport(filingId);

        Contracts.assertMatchesApiSchema("ReportResponse", report);
        assertThat(report.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(report.path("summary").path("overallRiskLevel").asString()).isEqualTo("NONE");
        assertThat(report.path("summary").path("totalFindings").asInt()).isZero();
        assertThat(report.path("summary").path("byCategory").isEmpty()).isTrue();
        assertThat(report.path("summary").path("bySeverity").isEmpty()).isTrue();
        assertThat(report.path("findings").isEmpty()).isTrue();
    }

    private static UUID acceptedAsSubmitted(final ApiResponse submitted) {
        assertThat(submitted.status()).as(submitted.toString()).isEqualTo(ACCEPTED);
        Contracts.assertMatchesApiSchema("SubmitFilingResponse", submitted.json());
        assertThat(submitted.json().path("status").asString()).isEqualTo("SUBMITTED");
        final UUID filingId = UUID.fromString(submitted.json().path("filingId").asString());
        assertThat(submitted.header("Location")).hasValue("/api/filings/" + filingId);
        return filingId;
    }

    /** The two transitions are too fast to catch by polling, so Ingestion's log is the evidence. */
    private static void assertWentThroughAnalyzing(final UUID filingId) {
        system.awaitLogLine(ComposeStack.INGESTION, "Filing " + filingId + " moved from SUBMITTED to ANALYZING");
        system.awaitLogLine(ComposeStack.INGESTION, "Filing " + filingId + " moved from ANALYZING to COMPLETED");
    }
}
