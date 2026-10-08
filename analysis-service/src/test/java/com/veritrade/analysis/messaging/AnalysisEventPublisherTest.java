package com.veritrade.analysis.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.veritrade.analysis.domain.AnalysisResult;
import com.veritrade.analysis.support.ContractFixtures;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.messaging.MessagingTopology;
import com.veritrade.contracts.model.RiskLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import tools.jackson.databind.json.JsonMapper;

class AnalysisEventPublisherTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(50);
    private static final String CORRELATION_ID = "corr-1";

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final AnalysisEventFactory events = new AnalysisEventFactory(
            Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC));
    private final AnalysisEventPublisher publisher =
            new AnalysisEventPublisher(rabbitTemplate, new MessagingProperties(SHORT_TIMEOUT));

    @BeforeEach
    void templateWithTheJsonConverter() {
        when(rabbitTemplate.getMessageConverter()).thenReturn(new JacksonJsonMessageConverter(JsonMapper.builder().build()));
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    static Stream<EventEnvelope<?>> allEvents() {
        UUID filingId = UUID.randomUUID();
        AnalysisEventFactory factory = new AnalysisEventFactory(Clock.systemUTC());
        AnalysisResult result = new AnalysisResult("1.0", List.of(), RiskLevel.NONE, Map.of());
        return Stream.of(
                factory.started(filingId, CORRELATION_ID),
                factory.completed(filingId, CORRELATION_ID, result),
                factory.failed(filingId, CORRELATION_ID, "boom"));
    }

    @ParameterizedTest
    @MethodSource("allEvents")
    void sendsTheEventWithTheContractPropertiesToItsRoutingKey(EventEnvelope<?> event) {
        confirmWith(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

        publisher.publish(event);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).send(eq(MessagingTopology.EVENTS_EXCHANGE), eq(event.eventType().routingKey()),
                sent.capture(), correlation.capture());
        MessageProperties properties = sent.getValue().getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(event.eventId().toString());
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(correlation.getValue().getId()).isEqualTo(event.eventId().toString());
        assertThat(ContractFixtures.validate(event.eventType(), sent.getValue().getBody())).isEmpty();
    }

    @Test
    void failsOnANegativeConfirm() {
        confirmWith(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "nack")));

        assertThatThrownBy(() -> publisher.publish(started()))
                .isInstanceOf(EventPublishException.class)
                .hasMessageContaining("negative confirm: nack");
    }

    @Test
    void failsWhenTheMessageIsReturnedAsUnroutable() {
        confirmWith(correlation -> {
            correlation.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE",
                    MessagingTopology.EVENTS_EXCHANGE, MessagingTopology.RK_ANALYSIS_STARTED));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertThatThrownBy(() -> publisher.publish(started()))
                .isInstanceOf(EventPublishException.class)
                .hasMessageContaining("unroutable, returned with NO_ROUTE");
    }

    @Test
    void failsWhenNoConfirmArrivesInTime() {
        assertThatThrownBy(() -> publisher.publish(started()))
                .isInstanceOf(EventPublishException.class)
                .hasMessageContaining("no confirm within " + SHORT_TIMEOUT);
    }

    @Test
    void failsAndKeepsTheInterruptFlagWhenInterrupted() {
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> publisher.publish(started()))
                .isInstanceOf(EventPublishException.class)
                .hasMessageContaining("interrupted");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void failsWhenTheConfirmFutureFails() {
        confirmWith(correlation -> correlation.getFuture().completeExceptionally(new IllegalStateException("closed")));

        assertThatThrownBy(() -> publisher.publish(started())).isInstanceOf(EventPublishException.class);
    }

    private EventEnvelope<?> started() {
        return events.started(UUID.randomUUID(), CORRELATION_ID);
    }

    private void confirmWith(Consumer<CorrelationData> broker) {
        doAnswer(invocation -> {
            broker.accept(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
