package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.e2e.support.Api;
import com.veritrade.e2e.support.ApiResponse;
import com.veritrade.e2e.support.Contracts;
import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.FilingRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Scenario 4: GET edge cases of /api/filings and /api/reports. */
class QueryEdgeCasesE2E extends E2ETestBase {

    private static final int OK = 200;
    private static final int BAD_REQUEST = 400;
    private static final int NOT_FOUND = 404;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int NEWEST_FIRST_SAMPLE = 3;
    private static final String UNKNOWN_ID = "00000000-0000-4000-8000-000000000000";

    @BeforeAll
    static void atLeastMoreFilingsThanTheDefaultLimit() {
        int missing = DEFAULT_LIMIT + 1 - list("?limit=" + MAX_LIMIT).size();
        IntStream.range(0, Math.max(0, missing))
                .forEach(i -> api.submitAccepted(FilingRequest.noRisk("Listing filler " + i)));
    }

    @Test
    void unknownFilingIdIsANotFoundProblem() {
        assertProblem(api.get(Api.FILINGS + "/" + UNKNOWN_ID), NOT_FOUND);
    }

    @Test
    void unknownReportIdIsANotFoundProblem() {
        assertProblem(api.get(Api.REPORTS + "/" + UNKNOWN_ID), NOT_FOUND);
    }

    /** Ingestion answers a malformed id with 400; Reporting with 404, because its OpenAPI operation has no 400. */
    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "123", "00000000-0000-4000-8000-00000000000Z"})
    void malformedIdIsABadRequestForFilingsAndANotFoundForReports(String id) {
        assertProblem(api.get(Api.FILINGS + "/" + id), BAD_REQUEST);
        assertProblem(api.get(Api.REPORTS + "/" + id), NOT_FOUND);
    }

    @Test
    void listWithoutLimitReturnsTheDefaultTwenty() {
        assertThat(list("")).hasSize(DEFAULT_LIMIT);
    }

    @Test
    void emptyLimitIsTreatedAsNoLimit() {
        assertThat(list("?limit=")).hasSize(DEFAULT_LIMIT);
    }

    @Test
    void limitOfOneReturnsOneFiling() {
        assertThat(list("?limit=1")).hasSize(1);
    }

    @Test
    void limitOfOneHundredIsAccepted() {
        List<JsonNode> filings = list("?limit=" + MAX_LIMIT);

        assertThat(filings).hasSizeGreaterThan(DEFAULT_LIMIT).hasSizeLessThanOrEqualTo(MAX_LIMIT);
        filings.forEach(filing -> Contracts.assertMatchesApiSchema("FilingStatusResponse", filing));
    }

    @ParameterizedTest
    @ValueSource(strings = {"101", "0", "-1", "abc", "1.5", "99999999999"})
    void limitOutsideOneToOneHundredOrNotAnIntegerIsABadRequestProblem(String limit) {
        assertProblem(api.get(Api.FILINGS + "?limit=" + limit), BAD_REQUEST);
    }

    @Test
    void listIsNewestFirst() {
        List<UUID> submitted = new ArrayList<>();
        IntStream.range(0, NEWEST_FIRST_SAMPLE)
                .forEach(i -> submitted.add(api.submitAccepted(FilingRequest.noRisk("Newest first " + i))));

        List<JsonNode> newest = list("?limit=" + NEWEST_FIRST_SAMPLE);

        assertThat(newest).extracting(filing -> UUID.fromString(filing.path("filingId").asString()))
                .containsExactlyElementsOf(submitted.reversed());
        assertThat(list("?limit=" + MAX_LIMIT))
                .extracting(filing -> Instant.parse(filing.path("submittedAt").asString()))
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    private static List<JsonNode> list(String query) {
        ApiResponse response = api.get(Api.FILINGS + query);
        assertThat(response.status()).as(response.toString()).isEqualTo(OK);
        return StreamSupport.stream(response.json().spliterator(), false).toList();
    }

    private static void assertProblem(ApiResponse response, int status) {
        assertThat(response.isProblem(status)).as(response.toString()).isTrue();
        Contracts.assertMatchesApiSchema("ProblemDetail", response.json());
    }
}
