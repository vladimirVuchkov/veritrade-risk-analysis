package com.veritrade.reporting;

import static com.veritrade.reporting.support.RabbitTestContainer.producerMessage;
import static com.veritrade.reporting.support.TestEvents.completed;
import static com.veritrade.reporting.support.TestEvents.completedEnvelope;
import static com.veritrade.reporting.support.TestEvents.failed;
import static com.veritrade.reporting.support.TestEvents.failedEnvelope;
import static com.veritrade.reporting.support.TestEvents.finding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.messaging.MessagingTopology;
import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import com.veritrade.reporting.repository.ProcessedEventRepository;
import com.veritrade.reporting.support.RabbitTestContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Reports are kept in the H2 file database: they survive a restart of the service. */
class RestartIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int OK = 200;

    @TempDir
    private Path dataDirectory;

    @Test
    void reportAndIdempotencyMarkersSurviveARestart() {
        UUID filingId = UUID.randomUUID();
        EventEnvelope<AnalysisCompletedPayload> event = completedEnvelope(completed(filingId, RiskLevel.HIGH,
                List.of(finding(RiskCategory.LEGAL, Severity.HIGH, 4))));
        String reportBeforeRestart;
        try (ConfigurableApplicationContext first = start()) {
            send(first, event);
            reportBeforeRestart = awaitReport(first, filingId);
        }

        try (ConfigurableApplicationContext second = start()) {
            assertThat(get(second, filingId).body()).isEqualTo(reportBeforeRestart);

            send(second, event);
            UUID sentinel = UUID.randomUUID();
            send(second, failedEnvelope(failed(sentinel, "sentinel")));
            awaitReport(second, sentinel);

            assertThat(get(second, filingId).body()).isEqualTo(reportBeforeRestart);
            assertThat(second.getBean(ProcessedEventRepository.class).existsById(event.eventId())).isTrue();
        }
    }

    private ConfigurableApplicationContext start() {
        Map<String, Object> properties = new HashMap<>(RabbitTestContainer.connectionProperties("restart-it"));
        properties.put("spring.datasource.url", "jdbc:h2:file:" + dataDirectory.resolve("reporting"));
        properties.put("server.port", 0);
        String[] arguments = properties.entrySet().stream()
                .map(property -> "--" + property.getKey() + "=" + property.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(ReportingApplication.class).run(arguments);
    }

    private static void send(ConfigurableApplicationContext context, EventEnvelope<?> event) {
        context.getBean(RabbitTemplate.class).send(MessagingTopology.EVENTS_EXCHANGE,
                event.eventType().routingKey(), producerMessage(event));
    }

    private static String awaitReport(ConfigurableApplicationContext context, UUID filingId) {
        return await().atMost(TIMEOUT)
                .until(() -> get(context, filingId), response -> response.statusCode() == OK)
                .body();
    }

    private static HttpResponse<String> get(ConfigurableApplicationContext context, UUID filingId) {
        String port = context.getEnvironment().getProperty("local.server.port");
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/reports/" + filingId)).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
