package com.veritrade.reporting;

import static com.veritrade.reporting.support.RabbitTestContainer.producerMessage;
import static com.veritrade.reporting.support.TestEvents.COMPLETED_EXAMPLE;
import static com.veritrade.reporting.support.TestEvents.completed;
import static com.veritrade.reporting.support.TestEvents.completedEnvelope;
import static com.veritrade.reporting.support.TestEvents.example;
import static com.veritrade.reporting.support.TestEvents.failed;
import static com.veritrade.reporting.support.TestEvents.failedEnvelope;
import static com.veritrade.reporting.support.TestEvents.finding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.messaging.MessagingTopology;
import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import com.veritrade.reporting.repository.ProcessedEventRepository;
import com.veritrade.reporting.support.OpenApiContract;
import com.veritrade.reporting.support.RabbitTestContainer;
import com.veritrade.reporting.support.TestEvents;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** End to end through a real broker: publish analysis results, read the report over REST. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reporting-flow-it;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ReportingFlowIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String DLQ = MessagingTopology.deadLetterQueue(MessagingTopology.Q_REPORTING_ANALYSIS_RESULTS);

    @DynamicPropertySource
    static void rabbit(final DynamicPropertyRegistry registry) {
        RabbitTestContainer.register(registry, "flow-it");
    }

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void emptyDeadLetterQueue() {
        admin.purgeQueue(DLQ, false);
    }

    @Test
    void publishedCompletedExampleBecomesTheReportOfTheExample() {
        final ObjectNode event = example(COMPLETED_EXAMPLE);
        publish(MessagingTopology.RK_ANALYSIS_COMPLETED, event.toString(), event.get("eventId").asString());

        final MvcTestResult result = awaitReport(UUID.fromString(event.get("payload").get("filingId").asString()));

        final JsonNode report = TestEvents.MAPPER.readTree(body(result));
        final JsonNode payload = event.get("payload");
        assertThat(OpenApiContract.validate("ReportResponse", body(result))).isEmpty();
        assertThat(report.get("filingId")).isEqualTo(payload.get("filingId"));
        assertThat(report.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(report.get("rulesVersion")).isEqualTo(payload.get("rulesVersion"));
        assertThat(report.get("findings")).isEqualTo(payload.get("findings"));
        final JsonNode summary = report.get("summary");
        assertThat(summary.get("totalFindings")).isEqualTo(payload.get("summary").get("totalFindings"));
        assertThat(summary.get("overallRiskLevel")).isEqualTo(payload.get("summary").get("overallRiskLevel"));
        assertThat(summary.get("byCategory")).isEqualTo(payload.get("summary").get("byCategory"));
        assertThat(summary.get("bySeverity")).isEqualTo(TestEvents.MAPPER.readTree("{\"HIGH\": 3}"));
    }

    @Test
    void doubleDeliveryGivesOneReport() {
        final UUID filingId = UUID.randomUUID();
        final EventEnvelope<?> event = completedEnvelope(completed(filingId, RiskLevel.HIGH, List.of(
                finding(RiskCategory.LEGAL, Severity.HIGH, 1), finding(RiskCategory.MARKET, Severity.LOW, 2))));

        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.RK_ANALYSIS_COMPLETED, producerMessage(event));
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.RK_ANALYSIS_COMPLETED, producerMessage(event));
        awaitAllConsumed();

        assertThat(processedEvents.existsById(event.eventId())).isTrue();
        assertThat(mvc.get().uri("/api/reports/{id}", filingId).exchange())
                .bodyJson().extractingPath("$.findings").asArray().hasSize(2);
        assertThat(rabbit.receive(DLQ)).isNull();
    }

    @Test
    void analysisFailedCreatesAFailedReport() {
        final UUID filingId = UUID.randomUUID();
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.RK_ANALYSIS_FAILED,
                producerMessage(failedEnvelope(failed(filingId, "Analysis failed after 3 attempts"))));

        final MvcTestResult result = awaitReport(filingId);

        assertThat(OpenApiContract.validate("ReportResponse", body(result))).isEmpty();
        assertThat(result).bodyJson().isLenientlyEqualTo("""
                {"status": "FAILED", "failureReason": "Analysis failed after 3 attempts", "summary": null, "findings": []}
                """);
    }

    @Test
    void lateContradictoryEventIsAcknowledgedAndIgnored() {
        final UUID filingId = UUID.randomUUID();
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.RK_ANALYSIS_COMPLETED,
                producerMessage(completedEnvelope(completed(filingId, RiskLevel.NONE, List.of()))));
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.RK_ANALYSIS_FAILED,
                producerMessage(failedEnvelope(failed(filingId, "late"))));
        awaitAllConsumed();

        assertThat(mvc.get().uri("/api/reports/{id}", filingId).exchange())
                .bodyJson().isLenientlyEqualTo("{\"status\": \"COMPLETED\", \"failureReason\": null}");
        assertThat(rabbit.receive(DLQ)).isNull();
    }

    @Test
    void reportIsNotFoundUntilTheEventArrives() {
        assertThat(mvc.get().uri("/api/reports/{id}", UUID.randomUUID()).exchange()).hasStatus(404);
    }

    private void publish(final String routingKey, final String json, final String eventId) {
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, routingKey,
                producerMessage(json, UUID.fromString(eventId), "it-correlation"));
    }

    /** One consumer, in order: once a sentinel event is stored, every earlier message was handled. */
    private void awaitAllConsumed() {
        final UUID sentinel = UUID.randomUUID();
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.RK_ANALYSIS_FAILED,
                producerMessage(failedEnvelope(failed(sentinel, "sentinel"))));
        awaitReport(sentinel);
    }

    private MvcTestResult awaitReport(final UUID filingId) {
        return await().atMost(TIMEOUT).until(
                () -> mvc.get().uri("/api/reports/{id}", filingId).exchange(),
                result -> result.getResponse().getStatus() == 200);
    }

    private static String body(final MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
