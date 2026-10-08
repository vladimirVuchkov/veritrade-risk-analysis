package com.veritrade.reporting.support;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Validates JSON against a component schema of docs/contracts/rest-api.openapi.yaml (OpenAPI 3.1 = JSON Schema 2020-12). */
public final class OpenApiContract {

    private static final String OPENAPI = "classpath:contracts/rest-api.openapi.yaml#/components/schemas/";
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private OpenApiContract() {
    }

    public static List<Error> validate(final String componentSchema, final String json) {
        final Schema schema = SCHEMAS.getSchema(SchemaLocation.of(OPENAPI + componentSchema));
        final JsonNode node = TestEvents.MAPPER.readTree(json);
        return schema.validate(node);
    }
}
