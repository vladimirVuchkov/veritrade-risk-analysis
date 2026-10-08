package com.veritrade.e2e;

import static com.veritrade.contracts.messaging.MessagingTopology.Q_ANALYSIS_FILING_SUBMITTED;
import static com.veritrade.contracts.messaging.MessagingTopology.Q_INGESTION_ANALYSIS_EVENTS;
import static com.veritrade.contracts.messaging.MessagingTopology.Q_REPORTING_ANALYSIS_RESULTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.e2e.support.ApiResponse;
import com.veritrade.e2e.support.ComposeStack;
import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.FilingRequest;
import com.veritrade.e2e.support.ReportAssertions;
import com.veritrade.e2e.support.Timeouts;
import com.veritrade.e2e.support.UpstreamProbe;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Scenarios 6 to 9: a service or the broker is down while filings are submitted and processed. */
class ServiceOutageE2E extends E2ETestBase {

    private static final int NOT_FOUND = 404;
    private static final int SERVICE_UNAVAILABLE = 503;
    private static final int ANALYSIS_EVENTS_PER_FILING = 2;

    @AfterEach
    void bringEverythingBackUp() {
        stack.ensureAllRunning();
    }

    @Test
    void filingStaysSubmittedWhileAnalysisIsDownAndCompletesAfterItRestarts() {
        stack.stop(ComposeStack.ANALYSIS);
        FilingRequest filing = system.demoFiling();
        UUID filingId = api.submitAccepted(filing);

        assertStaysSubmittedWithoutReport(filingId);
        awaitQueueDepthAtLeast(Q_ANALYSIS_FILING_SUBMITTED, 1);
        stack.start(ComposeStack.ANALYSIS);

        api.awaitStatus(filingId, "COMPLETED");
        ReportAssertions.assertConsistentCompletedReport(api.awaitReport(filingId), filing.content());
    }

    @Test
    void reportIsUnavailableWhileReportingIsDownAndAppearsAfterItRestarts() {
        UUID unknownReport = UUID.randomUUID();
        stopAndAssertFastServiceUnavailable(ComposeStack.REPORTING, () -> api.report(unknownReport));
        FilingRequest filing = system.demoFiling();
        UUID filingId = api.submitAccepted(filing);

        api.awaitStatus(filingId, "COMPLETED");
        ApiResponse report = api.report(filingId);
        assertThat(report.isProblem(SERVICE_UNAVAILABLE)).as(report.toString()).isTrue();
        awaitQueueDepthAtLeast(Q_REPORTING_ANALYSIS_RESULTS, 1);
        stack.start(ComposeStack.REPORTING);

        ReportAssertions.assertConsistentCompletedReport(api.awaitReport(filingId), filing.content());
    }

    @Test
    void analysisEventsWaitWhileIngestionIsDownAndTheStatusCatchesUpAfterItRestarts() {
        stack.stop(ComposeStack.ANALYSIS);
        FilingRequest filing = system.demoFiling();
        UUID filingId = api.submitAccepted(filing);
        system.awaitLogLine(ComposeStack.INGESTION, "Published event " + submittedEventId(filingId));
        stopAndAssertFastServiceUnavailable(ComposeStack.INGESTION, () -> api.filing(filingId));
        stack.start(ComposeStack.ANALYSIS);
        ReportAssertions.assertConsistentCompletedReport(api.awaitReport(filingId), filing.content());
        awaitQueueDepthAtLeast(Q_INGESTION_ANALYSIS_EVENTS, ANALYSIS_EVENTS_PER_FILING);
        stack.start(ComposeStack.INGESTION);

        api.awaitStatus(filingId, "COMPLETED");
        system.awaitLogLine(ComposeStack.INGESTION, "Filing " + filingId + " moved from SUBMITTED to ANALYZING");
    }

    @Test
    void outboxKeepsTheEventWhileTheBrokerIsDownAndTheFilingCompletesAfterTheBrokerRestarts() {
        stack.stop(ComposeStack.RABBITMQ);
        FilingRequest filing = system.demoFiling();
        UUID filingId = api.submitAccepted(filing);
        String eventId = submittedEventId(filingId).toString();

        assertStaysSubmittedWithoutReport(filingId);
        assertThat(system.countLogLines(ComposeStack.INGESTION, "Published event " + eventId)).isZero();
        stack.start(ComposeStack.RABBITMQ);

        api.awaitStatus(filingId, "COMPLETED");
        ReportAssertions.assertConsistentCompletedReport(api.awaitReport(filingId), filing.content());
        assertThat(system.countLogLines(ComposeStack.INGESTION, "Published event " + eventId)).isPositive();
    }

    @Test
    void unpublishedOutboxEventSurvivesAnIngestionRestartWhileTheBrokerIsDown() {
        stack.stop(ComposeStack.RABBITMQ);
        FilingRequest filing = system.demoFiling();
        UUID filingId = api.submitAccepted(filing);
        assertStaysSubmittedWithoutReport(filingId);
        assertThat(system.countLogLines(ComposeStack.INGESTION, "Published event " + submittedEventId(filingId))).isZero();

        stack.stop(ComposeStack.INGESTION);
        stack.startWithoutWaiting(ComposeStack.INGESTION);
        await("Ingestion answers again").atMost(Timeouts.SERVICE_START).pollInterval(Timeouts.POLL_INTERVAL)
                .ignoreExceptionsInstanceOf(UncheckedIOException.class)
                .until(() -> api.filing(filingId).status() != SERVICE_UNAVAILABLE);
        assertThat(api.status(filingId)).as("the accepted filing survived the restart").isEqualTo("SUBMITTED");
        stack.start(ComposeStack.RABBITMQ);

        api.awaitStatus(filingId, "COMPLETED");
        ReportAssertions.assertConsistentCompletedReport(api.awaitReport(filingId), filing.content());
    }

    private static void assertStaysSubmittedWithoutReport(UUID filingId) {
        await("filing " + filingId + " untouched").during(Timeouts.HOLD).atMost(Timeouts.HOLD.multipliedBy(2))
                .pollInterval(Timeouts.POLL_INTERVAL)
                .until(() -> api.status(filingId).equals("SUBMITTED") && api.report(filingId).status() == NOT_FOUND);
    }

    /**
     * Stops the service while a probe sends the request every 200 ms, and keeps probing until nginx's
     * cached address of the old container has expired. Every answer, also those that reach nginx while it
     * still holds that address, arrives within {@link Timeouts#UPSTREAM_UNAVAILABLE} (nginx's connect
     * timeout is 2 s), and every request sent after the stop is a 503 problem+json. No retry.
     */
    private static void stopAndAssertFastServiceUnavailable(String service, Supplier<ApiResponse> request) {
        List<UpstreamProbe.Result> results;
        long stoppedAt;
        try (UpstreamProbe probe = UpstreamProbe.start(request)) {
            stack.stop(service);
            stoppedAt = System.nanoTime();
            long cacheExpired = stoppedAt + Timeouts.UPSTREAM_ADDRESS_CACHE.toNanos();
            await("probes beyond nginx's cached upstream address")
                    .atMost(Timeouts.UPSTREAM_ADDRESS_CACHE.plus(Timeouts.HTTP_REQUEST))
                    .pollInterval(Timeouts.POLL_INTERVAL)
                    .until(() -> probe.hasResultStartedAfter(cacheExpired));
            results = probe.finish();
        }

        assertThat(results).as("every answer across the stop").allSatisfy(result -> {
            assertThat(result.error()).as(result.toString()).isNull();
            assertThat(result.elapsed()).as(result.toString()).isLessThan(Timeouts.UPSTREAM_UNAVAILABLE);
        });
        assertThat(results).filteredOn(result -> result.startedNanos() - stoppedAt >= 0)
                .as("answers after the stop").isNotEmpty()
                .allSatisfy(result -> assertThat(result.isProblem(SERVICE_UNAVAILABLE)).as(result.toString()).isTrue());
    }

    private static void awaitQueueDepthAtLeast(String queue, int messages) {
        await(queue + " holds " + messages + " message(s)").atMost(Timeouts.QUEUE_STATISTICS)
                .pollInterval(Timeouts.POLL_INTERVAL)
                .until(() -> broker.messageCount(queue) >= messages);
    }

    private static UUID submittedEventId(UUID filingId) {
        return EventIds.forFiling(filingId, EventType.FILING_SUBMITTED);
    }
}
