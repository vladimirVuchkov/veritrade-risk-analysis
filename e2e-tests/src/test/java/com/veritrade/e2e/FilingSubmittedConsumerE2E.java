package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.e2e.support.ComposeStack;
import com.veritrade.e2e.support.Contracts;
import com.veritrade.e2e.support.DeadLetters;
import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.Events;
import com.veritrade.e2e.support.FilingRequest;
import com.veritrade.e2e.support.MessageProperties;
import com.veritrade.e2e.support.ReportAssertions;
import com.veritrade.e2e.support.Timeouts;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scenarios 10, 14 and 15 for Analysis: filing.submitted messages that the outbox never sends
 * (a redelivery, a Java type header, unknown fields, a higher version), published by the test.
 * A message for a filing id that Ingestion does not know still gets a report, because Reporting keys
 * reports by filing id only; that makes a wrong result of Analysis visible through the REST API.
 */
class FilingSubmittedConsumerE2E extends E2ETestBase {

    private static final int REDELIVERED_ANALYSES = 2;

    @Test
    void redeliveredFilingSubmittedIsAnalysedAgainAndChangesNothing() {
        FilingRequest filing = system.demoFiling().withTitle("Redelivery " + UUID.randomUUID());
        UUID filingId = api.submitAccepted(filing);
        api.awaitStatus(filingId, "COMPLETED");
        String reportBefore = api.awaitReport(filingId).toString();
        UUID submittedId = EventIds.forFiling(filingId, EventType.FILING_SUBMITTED);

        broker.publishEvent(Events.filingSubmitted(submittedId, filingId, correlationId("redelivery"), filing));

        await("second analysis of " + filingId).atMost(Timeouts.MESSAGE_HANDLED).pollInterval(Timeouts.POLL_INTERVAL)
                .until(() -> system.countLogLines(ComposeStack.ANALYSIS, "Filing " + filingId + " analysed")
                        == REDELIVERED_ANALYSES);
        String completedId = EventIds.forFiling(filingId, EventType.ANALYSIS_COMPLETED).toString();
        system.awaitLogLine(ComposeStack.REPORTING, "Duplicate event ignored: eventId=" + completedId);
        system.awaitLogLine(ComposeStack.INGESTION, "Event " + completedId + " ignored: filing " + filingId
                + " is already COMPLETED");
        assertThat(api.status(filingId)).isEqualTo("COMPLETED");
        assertThat(api.report(filingId).json().toString()).isEqualTo(reportBefore);
        DeadLetters.assertNothingDeadLetteredFor(broker, filingId.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.veritrade.contracts.event.EventEnvelope", "java.lang.Runtime",
            "com.example.DoesNotExist", "java.util.HashMap"})
    void typeHeaderOnFilingSubmittedIsIgnoredByAnalysis(String typeId) {
        FilingRequest filing = system.demoFiling();
        UUID filingId = UUID.randomUUID();
        ObjectNode event = submittedEvent(filingId, filing);

        broker.publishEvent(event, MessageProperties.contract(event.path("eventId").asString(),
                event.path("correlationId").asString()).withHeader(MessageProperties.TYPE_ID_HEADER, typeId));

        ReportAssertions.assertConsistentCompletedReport(api.awaitReport(filingId), filing.content());
        assertThat(broker.peekMatching(DeadLetters.ANALYSIS_DLQ, filingId.toString())).isEmpty();
    }

    @Test
    void unknownFieldsInFilingSubmittedAreIgnoredByAnalysis() {
        FilingRequest filing = system.demoFiling();
        UUID filingId = UUID.randomUUID();
        ObjectNode event = submittedEvent(filingId, filing);
        event.put("producedBy", "a later release");
        ((ObjectNode) event.path("payload")).putObject("attachments").put("count", 0);

        broker.publishEvent(event);

        ReportAssertions.assertConsistentCompletedReport(api.awaitReport(filingId), filing.content());
    }

    /** Not specified by the contract: Analysis reads the fields it knows and analyses a version 2 filing. */
    @Test
    void higherEventVersionOfFilingSubmittedIsAnalysed() {
        FilingRequest filing = system.demoFiling();
        UUID filingId = UUID.randomUUID();
        ObjectNode event = submittedEvent(filingId, filing);
        event.put("eventVersion", 2);

        broker.publishEvent(event);

        ReportAssertions.assertConsistentCompletedReport(api.awaitReport(filingId), filing.content());
    }

    private static ObjectNode submittedEvent(UUID filingId, FilingRequest filing) {
        ObjectNode event = Events.filingSubmitted(EventIds.forFiling(filingId, EventType.FILING_SUBMITTED), filingId,
                correlationId("filing-submitted"), filing);
        Contracts.assertValidEvent(EventType.FILING_SUBMITTED, event);
        return event;
    }
}
