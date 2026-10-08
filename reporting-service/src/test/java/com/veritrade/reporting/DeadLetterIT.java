package com.veritrade.reporting;

import static com.veritrade.reporting.support.RabbitTestContainer.producerMessage;
import static com.veritrade.reporting.support.TestEvents.COMPLETED_EXAMPLE;
import static com.veritrade.reporting.support.TestEvents.completed;
import static com.veritrade.reporting.support.TestEvents.completedEnvelope;
import static com.veritrade.reporting.support.TestEvents.example;
import static com.veritrade.reporting.support.TestEvents.finding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.messaging.MessagingTopology;
import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import com.veritrade.reporting.messaging.AnalysisEventReader;
import com.veritrade.reporting.repository.ProcessedEventRepository;
import com.veritrade.reporting.repository.ReportRepository;
import com.veritrade.reporting.service.ReportService;
import com.veritrade.reporting.support.RabbitTestContainer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.node.ObjectNode;

/** Failure paths: unreadable messages go straight to the DLQ, processing failures after 3 attempts. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reporting-dlq-it;DB_CLOSE_DELAY=-1")
class DeadLetterIT {

    private static final String DLQ = MessagingTopology.deadLetterQueue(MessagingTopology.Q_REPORTING_ANALYSIS_RESULTS);
    private static final long DLQ_WAIT_MILLIS = Duration.ofSeconds(20).toMillis();
    private static final int CONTRACT_ATTEMPTS = 3;
    private static final Duration CONTRACT_BACKOFF = Duration.ofSeconds(3);

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        RabbitTestContainer.register(registry, "dead-letter-it");
    }

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private ReportRepository reports;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @MockitoSpyBean
    private AnalysisEventReader reader;

    @MockitoSpyBean
    private ReportService reportService;

    @BeforeEach
    void emptyDeadLetterQueue() {
        admin.purgeQueue(DLQ, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{this is not json", "[]", "{\"eventType\":\"ANALYSIS_EXPLODED\"}"})
    void unreadableMessageGoesStraightToTheDeadLetterQueueWithoutRetries(String body) {
        Message poison = producerMessage(body, UUID.randomUUID(), "poison");

        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.RK_ANALYSIS_COMPLETED, poison);

        Message deadLetter = rabbit.receive(DLQ, DLQ_WAIT_MILLIS);
        assertThat(deadLetter).isNotNull();
        assertThat(new String(deadLetter.getBody(), StandardCharsets.UTF_8)).isEqualTo(body);
        assertThat(deadLetter.getMessageProperties().getMessageId()).isEqualTo(poison.getMessageProperties().getMessageId());
        verify(reader, times(1)).read(any());
    }

    @Test
    void eventMissingRequiredPayloadFieldsGoesStraightToTheDeadLetterQueue() {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        ((ObjectNode) event.get("payload")).remove("summary");

        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.RK_ANALYSIS_COMPLETED,
                producerMessage(event.toString(), UUID.randomUUID(), "missing-summary"));

        assertThat(rabbit.receive(DLQ, DLQ_WAIT_MILLIS)).isNotNull();
        verify(reader, times(1)).read(any());
    }

    @Test
    void processingFailureIsRetriedExactlyThreeTimesThenDeadLettered() {
        UUID filingId = UUID.randomUUID();
        EventEnvelope<AnalysisCompletedPayload> event =
                completedEnvelope(completed(filingId, RiskLevel.LOW, List.of(finding(RiskCategory.LEGAL, Severity.LOW, 1))));
        doThrow(new IllegalStateException("database unavailable"))
                .when(reportService).recordCompleted(eq(event.eventId()), any());
        Instant start = Instant.now();

        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.RK_ANALYSIS_COMPLETED, producerMessage(event));

        Message deadLetter = rabbit.receive(DLQ, DLQ_WAIT_MILLIS);
        assertThat(deadLetter).isNotNull();
        assertThat(Duration.between(start, Instant.now())).isGreaterThanOrEqualTo(CONTRACT_BACKOFF);
        verify(reportService, times(CONTRACT_ATTEMPTS)).recordCompleted(eq(event.eventId()), any());
        assertThat(deadLetter.getMessageProperties().getMessageId()).isEqualTo(event.eventId().toString());
        assertThat(firstDeath(deadLetter)).containsEntry("reason", "rejected")
                .containsEntry("queue", MessagingTopology.Q_REPORTING_ANALYSIS_RESULTS);
        assertThat(reports.existsById(filingId)).isFalse();
        assertThat(processedEvents.existsById(event.eventId())).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstDeath(Message message) {
        List<Map<String, Object>> deaths = (List<Map<String, Object>>) message.getMessageProperties().getHeaders().get("x-death");
        assertThat(deaths).isNotEmpty();
        return deaths.getFirst();
    }
}
