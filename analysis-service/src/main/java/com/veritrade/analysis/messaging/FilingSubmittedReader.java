package com.veritrade.analysis.messaging;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.event.FilingSubmittedPayload;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and validates a {@code filing.submitted} message body. Unknown fields are ignored (tolerant
 * reader); the event type comes from the {@code eventType} field, never from a Java type header. A
 * newer {@code eventVersion} than {@link EventEnvelope#CURRENT_VERSION} is rejected without retries, so
 * the message can be replayed from the dead-letter queue after an upgrade.
 */
@Component
public class FilingSubmittedReader {

    private final ObjectReader reader;

    public FilingSubmittedReader(final JsonMapper jsonMapper) {
        this.reader = jsonMapper
                .readerFor(jsonMapper.getTypeFactory()
                        .constructParametricType(EventEnvelope.class, FilingSubmittedPayload.class))
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public EventEnvelope<FilingSubmittedPayload> read(final Message message) {
        final EventEnvelope<FilingSubmittedPayload> event = parse(message.getBody());
        validate(event);
        return event;
    }

    private EventEnvelope<FilingSubmittedPayload> parse(final byte[] body) {
        try {
            final EventEnvelope<FilingSubmittedPayload> event = reader.readValue(body);
            if (event == null) {
                throw new InvalidFilingMessageException("The message body is empty or null");
            }
            return event;
        } catch (final JacksonException e) {
            throw new InvalidFilingMessageException("Unreadable message body: " + e.getOriginalMessage(), e);
        }
    }

    private static void validate(final EventEnvelope<FilingSubmittedPayload> event) {
        if (event.eventType() != EventType.FILING_SUBMITTED) {
            throw new InvalidFilingMessageException("Unexpected eventType " + event.eventType()
                    + " on the filing.submitted queue (event " + event.eventId() + ")");
        }
        if (event.eventVersion() > EventEnvelope.CURRENT_VERSION) {
            throw new InvalidFilingMessageException("Unsupported eventVersion " + event.eventVersion()
                    + " of event " + event.eventId() + "; this service supports up to "
                    + EventEnvelope.CURRENT_VERSION + ", so the message waits in the dead-letter queue for a replay");
        }
        final List<String> missing = missingFields(event);
        if (!missing.isEmpty()) {
            throw new InvalidFilingMessageException(
                    "Missing or empty fields " + missing + " in event " + event.eventId());
        }
    }

    private static List<String> missingFields(final EventEnvelope<FilingSubmittedPayload> event) {
        final FilingSubmittedPayload payload = event.payload();
        final List<String> missing = new ArrayList<>();
        addIf(missing, event.correlationId().isBlank(), "correlationId");
        addIf(missing, payload.filingId() == null, "payload.filingId");
        addIf(missing, isBlank(payload.companyName()), "payload.companyName");
        addIf(missing, isBlank(payload.title()), "payload.title");
        addIf(missing, payload.content() == null || payload.content().isEmpty(), "payload.content");
        addIf(missing, payload.submittedAt() == null, "payload.submittedAt");
        return missing;
    }

    private static void addIf(final List<String> missing, final boolean condition, final String field) {
        if (condition) {
            missing.add(field);
        }
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
