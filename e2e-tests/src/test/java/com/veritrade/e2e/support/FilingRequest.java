package com.veritrade.e2e.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** A {@code POST /api/filings} body. */
public record FilingRequest(String companyName, String title, String content) {

    private static final String NO_RISK_CONTENT = "The company held its annual picnic in the park. "
            + "Attendance was strong, the weather was pleasant and everyone enjoyed the lemonade.";

    /** The demo filing used by smoke.sh: a 10-K excerpt with findings in every category. */
    public static FilingRequest demo(final Path repositoryRoot) {
        try {
            final JsonNode demo = Json.parse(Files.readString(repositoryRoot.resolve("scripts/demo-filing.json")));
            return new FilingRequest(demo.path("companyName").asString(), demo.path("title").asString(),
                    demo.path("content").asString());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Text that no risk rule matches. */
    public static FilingRequest noRisk(final String title) {
        return new FilingRequest("Quiet Meadow Picnic Co.", title, NO_RISK_CONTENT);
    }

    public FilingRequest withTitle(final String newTitle) {
        return new FilingRequest(companyName, newTitle, content);
    }

    public FilingRequest withCompanyName(final String newCompanyName) {
        return new FilingRequest(newCompanyName, title, content);
    }

    public FilingRequest withContent(final String newContent) {
        return new FilingRequest(companyName, title, newContent);
    }

    public ObjectNode toJsonNode() {
        final ObjectNode node = Json.object();
        node.put("companyName", companyName);
        node.put("title", title);
        node.put("content", content);
        return node;
    }

    /** The JSON body. Non-ASCII characters are written as UTF-8, not as {@code \\u} escapes. */
    public String toJson() {
        return Json.write(toJsonNode());
    }
}
