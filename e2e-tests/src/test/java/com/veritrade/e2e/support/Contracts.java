package com.veritrade.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.veritrade.contracts.event.EventType;
import tools.jackson.databind.JsonNode;

/** Validation against docs/contracts (copied to the test classpath under {@code contracts/}). */
public final class Contracts {

    private static final String SCHEMA_BASE = "https://veritrade.example/contracts/";
    private static final String OPENAPI_SCHEMAS = "classpath:contracts/rest-api.openapi.yaml#/components/schemas/";
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaIdResolvers(resolvers -> resolvers.mapPrefix(SCHEMA_BASE, "classpath:contracts/")));

    private Contracts() {
    }

    /** Asserts that a REST body matches a component schema of rest-api.openapi.yaml. */
    public static void assertMatchesApiSchema(String component, JsonNode body) {
        Schema schema = SCHEMAS.getSchema(SchemaLocation.of(OPENAPI_SCHEMAS + component));
        assertThat(schema.validate(body)).as("%s against %s", body, component).isEmpty();
    }

    /** Asserts that an event matches the JSON Schema of its type (which includes the envelope schema). */
    public static void assertValidEvent(EventType type, JsonNode event) {
        String stem = type.name().toLowerCase().replace('_', '-');
        Schema schema = SCHEMAS.getSchema(SchemaLocation.of(SCHEMA_BASE + stem + ".schema.json"));
        assertThat(schema.validate(event)).as("%s against %s", event, stem).isEmpty();
    }
}
