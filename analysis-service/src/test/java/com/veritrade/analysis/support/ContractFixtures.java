package com.veritrade.analysis.support;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.veritrade.contracts.event.EventType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The contract from docs/contracts (copied to the test classpath under contracts/) and other test resources. */
public final class ContractFixtures {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final String SCHEMA_BASE = "https://veritrade.example/contracts/";

    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaIdResolvers(resolvers -> resolvers.mapPrefix(SCHEMA_BASE, "classpath:contracts/")));

    private ContractFixtures() {
    }

    /** Schema violations of an event; empty when the event is valid. */
    public static List<Error> validate(EventType type, JsonNode event) {
        Schema schema = SCHEMAS.getSchema(SchemaLocation.of(SCHEMA_BASE + fileStem(type) + ".schema.json"));
        return schema.validate(event);
    }

    public static List<Error> validate(EventType type, byte[] body) {
        return validate(type, MAPPER.readTree(body));
    }

    public static ObjectNode example(EventType type) {
        return (ObjectNode) MAPPER.readTree(text("contracts/examples/" + fileStem(type) + ".json"));
    }

    public static String text(String classpathResource) {
        try (InputStream in = ContractFixtures.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + classpathResource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String fileStem(EventType type) {
        return type.name().toLowerCase().replace('_', '-');
    }
}
