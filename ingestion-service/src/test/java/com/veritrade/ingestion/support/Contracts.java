package com.veritrade.ingestion.support;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Access to the language-neutral contract in docs/contracts (copied to the test classpath by the build). */
public final class Contracts {

    private static final String SCHEMA_BASE = "https://veritrade.example/contracts/";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaIdResolvers(resolvers -> resolvers.mapPrefix(SCHEMA_BASE, "classpath:contracts/")));

    private Contracts() {
    }

    /** Schema validation errors of {@code json} against the schema of {@code type}; empty when valid. */
    public static List<Error> validate(final EventType type, final String json) {
        final Schema schema = SCHEMAS.getSchema(SchemaLocation.of(SCHEMA_BASE + fileStem(type) + ".schema.json"));
        return schema.validate(MAPPER.readTree(json));
    }

    public static List<Error> validateEnvelope(final String json) {
        final Schema schema = SCHEMAS.getSchema(SchemaLocation.of(SCHEMA_BASE + "event-envelope.schema.json"));
        return schema.validate(MAPPER.readTree(json));
    }

    /** A fresh, mutable copy of the example event of {@code type}. */
    public static ObjectNode example(final EventType type) {
        try (InputStream in = Contracts.class.getClassLoader()
                .getResourceAsStream("contracts/examples/" + fileStem(type) + ".json")) {
            if (in == null) {
                throw new IllegalStateException("Missing example for " + type);
            }
            return (ObjectNode) MAPPER.readTree(in);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The example file of {@code type}, byte for byte. */
    public static byte[] exampleBytes(final EventType type) {
        try (InputStream in = Contracts.class.getClassLoader()
                .getResourceAsStream("contracts/examples/" + fileStem(type) + ".json")) {
            if (in == null) {
                throw new IllegalStateException("Missing example for " + type);
            }
            return in.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The example event of {@code type}, rewritten for another filing (with its deterministic event id). */
    public static ObjectNode exampleFor(final EventType type, final UUID filingId) {
        final ObjectNode event = example(type);
        event.put("eventId", EventIds.forFiling(filingId, type).toString());
        ((ObjectNode) event.get("payload")).put("filingId", filingId.toString());
        return event;
    }

    private static String fileStem(final EventType type) {
        return type.name().toLowerCase().replace('_', '-');
    }
}
