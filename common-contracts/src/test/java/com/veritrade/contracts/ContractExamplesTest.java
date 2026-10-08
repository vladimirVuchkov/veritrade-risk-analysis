package com.veritrade.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Verifies the Java records against the language-neutral contract in docs/contracts:
 * every example is valid against its schema, maps to the records without loss, and is read tolerantly.
 */
class ContractExamplesTest {

    private static final String SCHEMA_BASE = "https://veritrade.example/contracts/";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaIdResolvers(resolvers -> resolvers.mapPrefix(SCHEMA_BASE, "classpath:contracts/")));

    @ParameterizedTest
    @EnumSource(EventType.class)
    void exampleIsValidAgainstItsSchema(final EventType type) {
        final Schema schema = SCHEMAS.getSchema(SchemaLocation.of(SCHEMA_BASE + fileStem(type) + ".schema.json"));

        final List<Error> errors = schema.validate(example(type));

        assertThat(errors).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void schemaRejectsAPayloadWithoutFilingIdOrWithTheWrongEventType(final EventType type) {
        final Schema schema = SCHEMAS.getSchema(SchemaLocation.of(SCHEMA_BASE + fileStem(type) + ".schema.json"));
        final ObjectNode missingFilingId = (ObjectNode) example(type);
        ((ObjectNode) missingFilingId.get("payload")).remove("filingId");
        final ObjectNode wrongType = (ObjectNode) example(type);
        wrongType.put("eventType", "UNKNOWN");

        assertThat(schema.validate(missingFilingId)).isNotEmpty();
        assertThat(schema.validate(wrongType)).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void exampleRoundTripsThroughTheRecordsWithoutLoss(final EventType type) {
        final JsonNode example = example(type);

        final EventEnvelope<?> envelope = MAPPER.readValue(example.toString(), envelopeType(type));

        assertThat(envelope.eventType()).isEqualTo(type);
        assertThat(envelope.payload()).isInstanceOf(type.payloadType());
        assertThat((JsonNode) MAPPER.valueToTree(envelope)).isEqualTo(example);
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void serializedRecordsAreValidAgainstTheSchema(final EventType type) {
        final EventEnvelope<?> envelope = MAPPER.readValue(example(type).toString(), envelopeType(type));
        final Schema schema = SCHEMAS.getSchema(SchemaLocation.of(SCHEMA_BASE + fileStem(type) + ".schema.json"));

        assertThat(schema.validate((JsonNode) MAPPER.valueToTree(envelope))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void unknownFieldsAreIgnored(final EventType type) {
        final ObjectNode example = (ObjectNode) example(type);
        example.put("addedInNewerVersion", "ignored");
        ((ObjectNode) example.get("payload")).put("anotherNewField", 42);

        final EventEnvelope<?> envelope = MAPPER.readValue(example.toString(), envelopeType(type));

        assertThat(envelope.eventType()).isEqualTo(type);
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void exampleEventIdIsTheDeterministicIdOfItsFiling(final EventType type) {
        final JsonNode example = example(type);
        final UUID filingId = UUID.fromString(example.get("payload").get("filingId").asString());

        assertThat(example.get("eventId").asString()).isEqualTo(EventIds.forFiling(filingId, type).toString());
    }

    private static JavaType envelopeType(final EventType type) {
        return MAPPER.getTypeFactory().constructParametricType(EventEnvelope.class, type.payloadType());
    }

    private static JsonNode example(final EventType type) {
        try (InputStream in = resource("contracts/examples/" + fileStem(type) + ".json")) {
            return MAPPER.readTree(in);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static InputStream resource(final String path) {
        final InputStream in = ContractExamplesTest.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("Missing test resource: " + path);
        }
        return in;
    }

    /** FILING_SUBMITTED -> filing-submitted */
    static String fileStem(final EventType type) {
        return type.name().toLowerCase().replace('_', '-');
    }
}
