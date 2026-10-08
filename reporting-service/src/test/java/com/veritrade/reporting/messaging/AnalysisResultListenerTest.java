package com.veritrade.reporting.messaging;

import static com.veritrade.reporting.support.TestEvents.COMPLETED_EXAMPLE;
import static com.veritrade.reporting.support.TestEvents.FAILED_EXAMPLE;
import static com.veritrade.reporting.support.TestEvents.example;
import static com.veritrade.reporting.support.TestEvents.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.AnalysisFailedPayload;
import com.veritrade.contracts.logging.CorrelationIds;
import com.veritrade.contracts.messaging.MessagingTopology;
import com.veritrade.reporting.service.RecordOutcome;
import com.veritrade.reporting.service.ReportService;
import com.veritrade.reporting.support.TestEvents;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;

class AnalysisResultListenerTest {

    private static final UUID COMPLETED_EVENT_ID = UUID.fromString("6c3c4f56-06d6-393c-96cc-67ed6a106eca");
    private static final UUID FAILED_EVENT_ID = UUID.fromString("83167d8a-e329-3629-bcde-9a13def226ab");
    private static final String EXAMPLE_CORRELATION_ID = "c0a8012e-5b1f-4d3c-8e2a-7f6b9d4c1a20";

    private final ReportService reportService = mock(ReportService.class);
    private final AnalysisResultListener listener = new AnalysisResultListener(
            new AnalysisEventReader(TestEvents.MAPPER, new AnalysisEventValidator(TestEvents.LIMITS)), reportService);

    @Test
    void dispatchesACompletedEventToTheReportService() {
        listener.onMessage(message(example(COMPLETED_EXAMPLE), MessagingTopology.RK_ANALYSIS_COMPLETED));

        verify(reportService).recordCompleted(eq(COMPLETED_EVENT_ID), any(AnalysisCompletedPayload.class));
    }

    @Test
    void dispatchesAFailedEventToTheReportService() {
        listener.onMessage(message(example(FAILED_EXAMPLE), MessagingTopology.RK_ANALYSIS_FAILED));

        verify(reportService).recordFailed(eq(FAILED_EVENT_ID), any(AnalysisFailedPayload.class));
    }

    @Test
    void lateOrDuplicateOutcomesReturnNormallySoTheMessageIsAcknowledged() {
        when(reportService.recordFailed(any(), any())).thenReturn(RecordOutcome.IGNORED_LATE);
        when(reportService.recordCompleted(any(), any())).thenReturn(RecordOutcome.DUPLICATE);

        listener.onMessage(message(example(FAILED_EXAMPLE), MessagingTopology.RK_ANALYSIS_FAILED));
        listener.onMessage(message(example(COMPLETED_EXAMPLE), MessagingTopology.RK_ANALYSIS_COMPLETED));

        verify(reportService).recordFailed(eq(FAILED_EVENT_ID), any());
    }

    @Test
    void putsTheEnvelopeCorrelationIdInTheLoggingContextAndClearsItAfterwards() {
        AtomicReference<String> seen = new AtomicReference<>();
        when(reportService.recordCompleted(any(), any())).thenAnswer(invocation -> {
            seen.set(MDC.get(CorrelationIds.MDC_KEY));
            return RecordOutcome.CREATED;
        });

        listener.onMessage(message(example(COMPLETED_EXAMPLE), MessagingTopology.RK_ANALYSIS_COMPLETED));

        assertThat(seen.get()).isEqualTo(EXAMPLE_CORRELATION_ID);
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    void clearsTheLoggingContextWhenTheMessageIsInvalid() {
        Message poison = message("{oops", MessagingTopology.RK_ANALYSIS_COMPLETED);

        assertThatThrownBy(() -> listener.onMessage(poison)).isInstanceOf(InvalidEventException.class);

        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
        verifyNoInteractions(reportService);
    }

    @Test
    void propagatesAProcessingFailureSoTheListenerRetryRuns() {
        when(reportService.recordCompleted(any(), any())).thenThrow(new IllegalStateException("database down"));
        Message message = message(example(COMPLETED_EXAMPLE), MessagingTopology.RK_ANALYSIS_COMPLETED);

        assertThatThrownBy(() -> listener.onMessage(message))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(InvalidEventException.class);
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }
}
