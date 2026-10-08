package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.Severity;
import com.veritrade.e2e.support.ComposeStack;
import com.veritrade.e2e.support.Contracts;
import com.veritrade.e2e.support.DeadLetters;
import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.Events;
import com.veritrade.e2e.support.FilingRequest;
import com.veritrade.e2e.support.MessageProperties;
import com.veritrade.e2e.support.ReportAssertions;
import com.veritrade.e2e.support.Timeouts;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scenarios 10 to 12, 14 and 15 for Ingestion and Reporting. Analysis is stopped, so the test plays
 * the producer of the analysis events and controls their order. When Analysis is started at the end,
 * its real results for the same filings arrive late and must change nothing.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ControlledAnalysisEventsE2E extends E2ETestBase {

    private static final int LAST = Integer.MAX_VALUE;
    private static final int NOT_FOUND = 404;
    private static final String FAILURE_REASON = "Analysis failed after all retries: IllegalStateException: e2e";
    private static final String EMOJI = "📈";
    private static final int MAX_MATCHED_TEXT_UNITS = 500;
    private static final int EMOJI_UNITS = 2;
    private static final int MAX_REASON_UNITS = 1000;

    /** Final status and report of each filing whose outcome must survive the late real analysis. */
    private static final Map<UUID, Outcome> SETTLED = new ConcurrentHashMap<>();

    private record Outcome(String status, String failureReason, String report) {
    }

    @BeforeAll
    static void stopAnalysis() {
        stack.stop(ComposeStack.ANALYSIS);
    }

    @AfterAll
    static void bringEverythingBackUp() {
        stack.ensureAllRunning();
    }

    @Test
    void duplicateAnalysisCompletedGivesOneReportWithoutDuplicateFindings() {
        final UUID filingId = submit();
        final ObjectNode completed = completed(filingId, twoFindings());

        broker.publishEvent(completed);
        broker.publishEvent(completed);

        final String eventId = completed.path("eventId").asString();
        system.awaitLogLine(ComposeStack.REPORTING, "Duplicate event ignored: eventId=" + eventId);
        system.awaitLogLine(ComposeStack.INGESTION, "Event " + eventId + " ignored: filing " + filingId + " is already COMPLETED");
        final JsonNode report = api.awaitReport(filingId);
        assertThat(ReportAssertions.findings(report)).hasSize(2);
        settle(filingId, "COMPLETED");
    }

    @Test
    void duplicateAnalysisFailedGivesOneFailedReport() {
        final UUID filingId = submit();
        final ObjectNode failed = failed(filingId);

        broker.publishEvent(failed);
        broker.publishEvent(failed);

        final String eventId = failed.path("eventId").asString();
        system.awaitLogLine(ComposeStack.REPORTING, "Duplicate event ignored: eventId=" + eventId);
        system.awaitLogLine(ComposeStack.INGESTION, "Event " + eventId + " ignored: filing " + filingId + " is already FAILED");
        assertFailedEverywhere(filingId);
        settle(filingId, "FAILED");
    }

    @Test
    void analysisCompletedBeforeAnalysisStartedEndsCompleted() {
        final UUID filingId = submit();
        final ObjectNode started = Events.started(UUID.randomUUID(), filingId, correlationId("order"));

        broker.publishEvent(completed(filingId, twoFindings()));
        broker.publishEvent(started);

        system.awaitLogLine(ComposeStack.INGESTION, "Late or contradictory event " + started.path("eventId").asString(),
                "is COMPLETED, event requests ANALYZING");
        assertThat(api.status(filingId)).isEqualTo("COMPLETED");
        api.awaitReport(filingId);
        settle(filingId, "COMPLETED");
    }

    @Test
    void analysisFailedAfterCompletedIsIgnoredByIngestionAndReporting() {
        final UUID filingId = submit();
        broker.publishEvent(completed(filingId, twoFindings()));
        api.awaitStatus(filingId, "COMPLETED");
        final String reportBefore = api.awaitReport(filingId).toString();
        final ObjectNode failed = failed(filingId);

        broker.publishEvent(failed);

        assertLateEventIgnoredByBoth(failed, filingId, "COMPLETED", "FAILED");
        assertThat(api.report(filingId).json().toString()).isEqualTo(reportBefore);
        settle(filingId, "COMPLETED");
    }

    @Test
    void analysisCompletedAfterFailedIsIgnoredAndBothServicesAgreeOnFailed() {
        final UUID filingId = submit();
        final ObjectNode completed = completed(filingId, twoFindings());

        broker.publishEvent(failed(filingId));
        broker.publishEvent(completed);

        assertLateEventIgnoredByBoth(completed, filingId, "FAILED", "COMPLETED");
        assertFailedEverywhere(filingId);
        settle(filingId, "FAILED");
    }

    @Test
    void analysisFailureReachesFailedWithTheReasonInIngestionAndReporting() {
        final UUID filingId = submit();

        broker.publishEvent(Events.started(UUID.randomUUID(), filingId, correlationId("failure")));
        broker.publishEvent(failed(filingId));

        assertFailedEverywhere(filingId);
        system.awaitLogLine(ComposeStack.INGESTION, "Filing " + filingId + " moved from SUBMITTED to ANALYZING");
        system.awaitLogLine(ComposeStack.INGESTION, "Filing " + filingId + " moved from ANALYZING to FAILED");
        DeadLetters.assertNothingDeadLetteredFor(broker, filingId.toString());
        settle(filingId, "FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.veritrade.contracts.event.EventEnvelope", "java.lang.Runtime",
            "com.example.DoesNotExist", "java.util.HashMap"})
    void typeHeaderIsIgnoredByIngestionAndReporting(final String typeId) {
        final UUID filingId = submit();
        final ObjectNode started = Events.started(UUID.randomUUID(), filingId, correlationId("type-header"));
        final ObjectNode completed = completed(filingId, twoFindings());

        broker.publishEvent(started, withTypeHeader(started, typeId));
        broker.publishEvent(completed, withTypeHeader(completed, typeId));

        api.awaitStatus(filingId, "COMPLETED");
        assertThat(ReportAssertions.findings(api.awaitReport(filingId))).hasSize(2);
        system.awaitLogLine(ComposeStack.INGESTION, "Filing " + filingId + " moved from SUBMITTED to ANALYZING");
        DeadLetters.assertNothingDeadLetteredFor(broker, filingId.toString());
        settle(filingId, "COMPLETED");
    }

    @Test
    void unknownFieldsAtEveryLevelAreIgnored() {
        final UUID filingId = submit();
        final ObjectNode started = Events.started(UUID.randomUUID(), filingId, correlationId("unknown-fields"));
        final ObjectNode completed = completed(filingId, twoFindings());
        List.of(started, completed, (ObjectNode) started.path("payload"), (ObjectNode) completed.path("payload"),
                        (ObjectNode) completed.path("payload").path("summary"),
                        (ObjectNode) completed.path("payload").path("findings").path(0))
                .forEach(node -> node.putObject("addedInALaterRelease").put("hint", "ignore me"));

        broker.publishEvent(started);
        broker.publishEvent(completed);

        api.awaitStatus(filingId, "COMPLETED");
        system.awaitLogLine(ComposeStack.INGESTION, "Filing " + filingId + " moved from SUBMITTED to ANALYZING");
        assertThat(ReportAssertions.findings(api.awaitReport(filingId))).hasSize(2);
        DeadLetters.assertNothingDeadLetteredFor(broker, filingId.toString());
        settle(filingId, "COMPLETED");
    }

    /**
     * Contract, "Event versioning": a consumer dead-letters an eventVersion above the one it supports, without
     * retries, so it can be replayed after an upgrade. Ingestion and Reporting both do, so nothing changes.
     */
    @Test
    void higherEventVersionIsDeadLetteredByIngestionAndReporting() {
        final UUID filingId = submit();
        final ObjectNode completed = completed(filingId, twoFindings());
        completed.put("eventVersion", EventEnvelope.CURRENT_VERSION + 1);

        broker.publishEvent(completed);

        final String eventId = completed.path("eventId").asString();
        DeadLetters.awaitDeadLettered(broker, DeadLetters.INGESTION_DLQ, eventId);
        DeadLetters.awaitDeadLettered(broker, DeadLetters.REPORTING_DLQ, eventId);
        assertThat(api.status(filingId)).isEqualTo("SUBMITTED");
        assertThat(api.report(filingId).status()).isEqualTo(NOT_FOUND);
    }

    /** Contract, "Text limits": a reason one UTF-16 unit over 1000 is dead-lettered by Ingestion, not cut. */
    @Test
    void failureReasonOverTheUtf16LimitIsDeadLetteredByIngestion() {
        final UUID filingId = submit();
        final String overLimit = "r".repeat(MAX_REASON_UNITS - 1) + EMOJI;
        final ObjectNode failed = Events.failed(UUID.randomUUID(), filingId, correlationId("reason-over-limit"), overLimit);

        broker.publishEvent(failed);

        DeadLetters.awaitDeadLettered(broker, DeadLetters.INGESTION_DLQ, failed.path("eventId").asString());
        assertThat(api.status(filingId)).isEqualTo("SUBMITTED");
    }

    /** The contract counts text limits in UTF-16 units: 250 emoji are 500 units, at the matchedText limit. */
    @Test
    void reportingAcceptsMatchedTextAtTheUtf16LimitAndStoresItIntact() {
        final UUID filingId = submit();
        final String atLimit = EMOJI.repeat(MAX_MATCHED_TEXT_UNITS / EMOJI_UNITS);

        broker.publishEvent(completed(filingId, List.of(
                Events.finding(RiskCategory.MARKET, Severity.LOW, "MKT-001", atLimit, 0))));

        final JsonNode report = api.awaitReport(filingId);
        assertThat(report.path("findings").path(0).path("matchedText").asString()).isEqualTo(atLimit);
        settle(filingId, "COMPLETED");
    }

    /** One UTF-16 unit over the limit is invalid, also when it is still only 251 code points. */
    @ParameterizedTest
    @ValueSource(strings = {"ascii", "emoji"})
    void reportingDeadLettersMatchedTextOneUtf16UnitOverTheLimit(final String kind) {
        final UUID filingId = submit();
        final String overLimit = kind.equals("ascii")
                ? "m".repeat(MAX_MATCHED_TEXT_UNITS + 1)
                : EMOJI.repeat(MAX_MATCHED_TEXT_UNITS / EMOJI_UNITS) + "m";
        final ObjectNode completed = Events.completed(UUID.randomUUID(), filingId, correlationId("over-limit"), List.of(
                Events.finding(RiskCategory.MARKET, Severity.LOW, "MKT-001", overLimit, 0)));

        broker.publishEvent(completed);

        DeadLetters.awaitDeadLettered(broker, DeadLetters.REPORTING_DLQ, completed.path("eventId").asString());
        assertThat(api.report(filingId).status()).isEqualTo(NOT_FOUND);
    }

    @Test
    @Order(LAST)
    void realAnalysisResultsArrivingAfterTheRestartChangeNothing() {
        assertThat(SETTLED).isNotEmpty();
        stack.start(ComposeStack.ANALYSIS);

        SETTLED.forEach((filingId, outcome) -> {
            system.awaitLogLine(Timeouts.PROCESSING, ComposeStack.ANALYSIS, "Filing " + filingId + " analysed");
            system.awaitLogLine(ComposeStack.REPORTING, "Late or contradictory event ignored", filingId.toString());
            assertThat(outcome(filingId)).isEqualTo(outcome);
            DeadLetters.assertNothingDeadLetteredFor(broker, filingId.toString());
        });
    }

    private static UUID submit() {
        return api.submitAccepted(system.demoFiling().withTitle("Controlled events " + UUID.randomUUID()));
    }

    private static ObjectNode completed(final UUID filingId, final List<ObjectNode> findings) {
        final ObjectNode event = Events.completed(UUID.randomUUID(), filingId, correlationId("controlled"), findings);
        Contracts.assertValidEvent(EventType.ANALYSIS_COMPLETED, event);
        return event;
    }

    private static ObjectNode failed(final UUID filingId) {
        final ObjectNode event = Events.failed(UUID.randomUUID(), filingId, correlationId("controlled"), FAILURE_REASON);
        Contracts.assertValidEvent(EventType.ANALYSIS_FAILED, event);
        return event;
    }

    private static List<ObjectNode> twoFindings() {
        return List.of(
                Events.finding(RiskCategory.LEGAL, Severity.HIGH, "LEGAL-001", "pending litigation", 10),
                Events.finding(RiskCategory.MARKET, Severity.LOW, "MKT-001", "intense competition", 40));
    }

    private static MessageProperties withTypeHeader(final ObjectNode event, final String typeId) {
        return MessageProperties.contract(event.path("eventId").asString(), event.path("correlationId").asString())
                .withHeader(MessageProperties.TYPE_ID_HEADER, typeId);
    }

    private static void assertFailedEverywhere(final UUID filingId) {
        final JsonNode filing = api.awaitStatus(filingId, "FAILED");
        assertThat(filing.path("failureReason").asString()).isEqualTo(FAILURE_REASON);
        final JsonNode report = api.awaitReport(filingId);
        Contracts.assertMatchesApiSchema("ReportResponse", report);
        assertThat(report.path("status").asString()).isEqualTo("FAILED");
        assertThat(report.path("failureReason").asString()).isEqualTo(FAILURE_REASON);
        assertThat(report.path("summary").isNull()).isTrue();
        assertThat(report.path("findings").isEmpty()).isTrue();
    }

    private static void assertLateEventIgnoredByBoth(final ObjectNode late, final UUID filingId, final String kept, final String requested) {
        final String eventId = late.path("eventId").asString();
        system.awaitLogLine(ComposeStack.INGESTION, "Late or contradictory event " + eventId,
                "is " + kept + ", event requests " + requested);
        system.awaitLogLine(ComposeStack.REPORTING, "Late or contradictory event ignored", "eventId=" + eventId,
                "reportStatus=" + kept, "eventStatus=" + requested);
        await().atMost(Timeouts.PROCESSING).until(() -> api.report(filingId).json().path("status").asString().equals(kept));
        assertThat(api.status(filingId)).isEqualTo(kept);
        DeadLetters.assertNothingDeadLetteredFor(broker, eventId);
    }

    private static void settle(final UUID filingId, final String status) {
        api.awaitStatus(filingId, status);
        api.awaitReport(filingId);
        SETTLED.put(filingId, outcome(filingId));
    }

    private static Outcome outcome(final UUID filingId) {
        final JsonNode filing = api.filing(filingId).json();
        return new Outcome(filing.path("status").asString(), filing.path("failureReason").asString(""),
                api.report(filingId).body());
    }
}
