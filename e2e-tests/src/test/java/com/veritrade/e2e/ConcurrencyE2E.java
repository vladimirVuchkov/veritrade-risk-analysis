package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.veritrade.e2e.support.DeadLetters;
import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.FilingRequest;
import com.veritrade.e2e.support.ReportAssertions;
import com.veritrade.e2e.support.Timeouts;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Scenario 16: many filings submitted at the same time. */
class ConcurrencyE2E extends E2ETestBase {

    private static final int PARALLEL_FILINGS = 30;

    @Test
    void parallelFilingsAllCompleteWithOneConsistentReportEachAndNothingDeadLettered() {
        DeadLetters.ALL.forEach(broker::purge);
        FilingRequest filing = system.demoFiling();

        List<UUID> filingIds = submitInParallel(filing);

        assertThat(Set.copyOf(filingIds)).hasSize(PARALLEL_FILINGS);
        List<JsonNode> reports = filingIds.stream().map(ConcurrencyE2E::completedReport).toList();
        reports.forEach(report -> ReportAssertions.assertConsistentCompletedReport(report, filing.content()));
        Set<String> expectedFindings = findingKeys(reports.getFirst());
        assertThat(reports).allSatisfy(report -> assertThat(findingKeys(report)).isEqualTo(expectedFindings));
        DeadLetters.ALL.forEach(queue -> assertThat(broker.peek(queue)).as(queue).isEmpty());
        await("statistics show empty dead-letter queues").atMost(Timeouts.QUEUE_STATISTICS)
                .pollInterval(Timeouts.POLL_INTERVAL)
                .until(() -> DeadLetters.ALL.stream().allMatch(queue -> broker.messageCount(queue) == 0));
    }

    private static List<UUID> submitInParallel(FilingRequest filing) {
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_FILINGS)) {
            List<Future<UUID>> submissions = IntStream.range(0, PARALLEL_FILINGS)
                    .mapToObj(i -> pool.submit(() -> api.submitAccepted(filing.withTitle("Parallel " + i))))
                    .toList();
            return submissions.stream().map(ConcurrencyE2E::result).toList();
        }
    }

    private static JsonNode completedReport(UUID filingId) {
        api.awaitStatus(filingId, "COMPLETED");
        return api.awaitReport(filingId);
    }

    private static UUID result(Future<UUID> submission) {
        try {
            return submission.get();
        } catch (Exception e) {
            throw new AssertionError("a parallel submit failed", e);
        }
    }

    private static Set<String> findingKeys(JsonNode report) {
        return ReportAssertions.findings(report).stream().map(ReportAssertions::findingKey)
                .collect(Collectors.toSet());
    }
}
