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
import java.io.UncheckedIOException;
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
        stack.stop(ComposeStack.REPORTING);
        FilingRequest filing = system.demoFiling();
        UUID filingId = api.submitAccepted(filing);

        api.awaitStatus(filingId, "COMPLETED");
        awaitServiceUnavailable(() -> api.report(filingId));
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
        stack.stop(ComposeStack.INGESTION);

        awaitServiceUnavailable(() -> api.filing(filingId));
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
     * nginx answers 503 problem+json for a stopped upstream. Right after the stop it may still try the
     * cached address of the old container for a while (see the handoff note), so the request is retried.
     */
    private static void awaitServiceUnavailable(Supplier<ApiResponse> request) {
        await("503 problem+json").atMost(Timeouts.UPSTREAM_GONE).pollInterval(Timeouts.POLL_INTERVAL)
                .ignoreExceptionsInstanceOf(UncheckedIOException.class)
                .until(() -> request.get().isProblem(SERVICE_UNAVAILABLE));
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
