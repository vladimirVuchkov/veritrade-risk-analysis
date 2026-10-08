package com.veritrade.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import com.veritrade.reporting.domain.FindingEntity;
import com.veritrade.reporting.domain.Report;
import com.veritrade.reporting.service.ReportService;
import com.veritrade.reporting.service.ReportView;
import com.veritrade.reporting.support.OpenApiContract;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(ReportController.class)
class ReportControllerTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-10-07T12:00:03Z");
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ReportService reportService;

    private final UUID filingId = UUID.fromString("3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12");

    @Test
    void completedReportMatchesTheOpenApiSchema() {
        Report report = Report.completed(filingId, RiskLevel.HIGH, 3, "1.0", GENERATED_AT);
        report.addFinding(new FindingEntity(RiskCategory.LEGAL, Severity.HIGH, "LEGAL-001", "pending litigation", "We face pending litigation.", 41));
        report.addFinding(new FindingEntity(RiskCategory.CYBERSECURITY, Severity.CRITICAL, "CYBER-001", "breach", "A breach happened.", 211));
        report.addFinding(new FindingEntity(RiskCategory.LEGAL, Severity.LOW, "LEGAL-002", "lawsuit", "A lawsuit.", 5));
        when(reportService.findReport(filingId)).thenReturn(Optional.of(view(report)));

        MvcTestResult result = mvc.get().uri("/api/reports/{id}", filingId).exchange();

        assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(OpenApiContract.validate("ReportResponse", body(result))).isEmpty();
        assertThat(result).bodyJson().isLenientlyEqualTo("""
                {
                  "filingId": "3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12",
                  "status": "COMPLETED",
                  "generatedAt": "2026-10-07T12:00:03Z",
                  "rulesVersion": "1.0",
                  "failureReason": null,
                  "summary": {
                    "totalFindings": 3,
                    "overallRiskLevel": "HIGH",
                    "byCategory": {"LEGAL": 2, "CYBERSECURITY": 1},
                    "bySeverity": {"CRITICAL": 1, "HIGH": 1, "LOW": 1}
                  }
                }
                """);
        assertThat(result).bodyJson().extractingPath("$.findings[*].ruleId")
                .asArray().containsExactly("CYBER-001", "LEGAL-001", "LEGAL-002");
        assertThat(result).bodyJson().extractingPath("$.findings[0]").isEqualTo(Map.of(
                "category", "CYBERSECURITY", "severity", "CRITICAL", "ruleId", "CYBER-001",
                "matchedText", "breach", "excerpt", "A breach happened.", "position", 211));
    }

    @Test
    void reportWithoutFindingsHasLevelNoneAndEmptyMaps() {
        when(reportService.findReport(filingId))
                .thenReturn(Optional.of(view(Report.completed(filingId, RiskLevel.NONE, 0, "1.0", GENERATED_AT))));

        MvcTestResult result = mvc.get().uri("/api/reports/{id}", filingId).exchange();

        assertThat(result).hasStatusOk();
        assertThat(OpenApiContract.validate("ReportResponse", body(result))).isEmpty();
        assertThat(result).bodyJson().isLenientlyEqualTo("""
                {"summary": {"totalFindings": 0, "overallRiskLevel": "NONE", "byCategory": {}, "bySeverity": {}},
                 "findings": []}
                """);
    }

    @Test
    void failedReportHasTheReasonNoSummaryAndNoFindings() {
        when(reportService.findReport(filingId)).thenReturn(Optional.of(
                view(Report.failed(filingId, "Analysis failed after 3 attempts: rule engine error", GENERATED_AT))));

        MvcTestResult result = mvc.get().uri("/api/reports/{id}", filingId).exchange();

        assertThat(result).hasStatusOk();
        assertThat(OpenApiContract.validate("ReportResponse", body(result))).isEmpty();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {
                  "filingId": "3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12",
                  "status": "FAILED",
                  "generatedAt": "2026-10-07T12:00:03Z",
                  "rulesVersion": null,
                  "failureReason": "Analysis failed after 3 attempts: rule engine error",
                  "summary": null,
                  "findings": []
                }
                """);
    }

    @Test
    void reportNotReadyYetIsA404ProblemDetail() {
        when(reportService.findReport(any())).thenReturn(Optional.empty());

        MvcTestResult result = mvc.get().uri("/api/reports/{id}", filingId).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(OpenApiContract.validate("ProblemDetail", body(result))).isEmpty();
        assertThat(result).bodyJson().isLenientlyEqualTo("""
                {"status": 404, "title": "Report not found",
                 "detail": "No report for filing 3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12 yet",
                 "instance": "/api/reports/3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12"}
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "123", "3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a1Z", "%20"})
    void malformedFilingIdIsA404ProblemDetailAsTheOpenApiDefinesNo400(String filingId) {
        MvcTestResult result = mvc.get().uri("/api/reports/" + filingId).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(OpenApiContract.validate("ProblemDetail", body(result))).isEmpty();
        assertThat(result).bodyJson().isLenientlyEqualTo("""
                {"status": 404, "title": "Report not found", "detail": "Filing id is not a valid UUID"}
                """);
        verifyNoInteractions(reportService);
    }

    @Test
    void theSchemaCheckRejectsABodyThatBreaksTheContract() {
        assertThat(OpenApiContract.validate("ReportResponse", """
                {"filingId": "x", "status": "PENDING", "generatedAt": "2026-10-07T12:00:03Z", "findings": []}
                """)).isNotEmpty();
        assertThat(OpenApiContract.validate("ReportResponse", """
                {"filingId": "x", "status": "COMPLETED", "generatedAt": "2026-10-07T12:00:03Z"}
                """)).isNotEmpty();
    }

    @Test
    void unsupportedMethodIsAProblemDetail() {
        MvcTestResult result = mvc.post().uri("/api/reports/{id}", filingId).exchange();

        assertThat(result).hasStatus(HttpStatus.METHOD_NOT_ALLOWED).hasContentTypeCompatibleWith(PROBLEM_JSON);
    }

    private static ReportView view(Report report) {
        return new ReportView(report, report.orderedFindings(), report.countByCategory(), report.countBySeverity());
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
