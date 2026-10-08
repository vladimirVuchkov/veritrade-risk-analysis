package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.e2e.support.ApiResponse;
import com.veritrade.e2e.support.E2ETestBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Scenario 17: the UI served by nginx (MIME types, hidden files, security headers including the CSP). */
class StaticUiE2E extends E2ETestBase {

    private static final int OK = 200;
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    @Test
    void rootServesIndexHtmlWithTheStaticSecurityHeaders() {
        final ApiResponse response = api.get("/");

        assertThat(response.status()).isEqualTo(OK);
        assertThat(response.contentType()).startsWith("text/html");
        assertThat(response.body()).contains("<script type=\"module\"");
        assertStaticHeaders(response);
    }

    @Test
    void indexHtmlNeedsNoInlineScriptOrStyleSoTheContentSecurityPolicyHolds() {
        final String html = api.get("/").body();

        assertThat(html).doesNotContainPattern("<script(?![^>]*\\bsrc=)[^>]*>")
                .doesNotContainPattern("(?i)<style[\\s>]")
                .doesNotContainPattern("(?i)\\sstyle=")
                .doesNotContainPattern("(?i)\\son[a-z]+=")
                .doesNotContain("javascript:");
    }

    @ParameterizedTest
    @MethodSource("javaScriptModules")
    void everyJavaScriptModuleHasAJavaScriptMimeType(final String module) {
        final ApiResponse response = api.get("/js/" + module);

        assertThat(response.status()).isEqualTo(OK);
        assertThat(response.contentType()).matches("(application|text)/javascript.*");
        assertStaticHeaders(response);
    }

    @Test
    void stylesheetIsServedAsCss() {
        final ApiResponse response = api.get("/styles.css");

        assertThat(response.status()).isEqualTo(OK);
        assertThat(response.contentType()).startsWith("text/css");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/mock/", "/mock/server.js", "/test/", "/test/api.test.js", "/test/index.js",
            "/package.json", "/README.md", "/.env"})
    void developmentFilesAreNotServed(final String path) {
        assertThat(api.get(path).status()).isEqualTo(NOT_FOUND);
    }

    @Test
    void directoryListingIsRefused() {
        final ApiResponse response = api.get("/js/");

        assertThat(response.status()).isEqualTo(FORBIDDEN);
        assertThat(response.body()).doesNotContain("app.js");
    }

    @Test
    void unknownApiPathIsANotFoundProblem() {
        final ApiResponse response = api.get("/api/unknown");

        assertThat(response.isProblem(NOT_FOUND)).as(response.toString()).isTrue();
    }

    @Test
    void apiResponsesCarryTheServerWideSecurityHeaders() {
        final ApiResponse response = api.get("/api/filings?limit=1");

        assertSecurityHeaders(response);
    }

    @Test
    void problemResponsesOfNginxCarryTheServerWideSecurityHeaders() {
        final ApiResponse response = api.get("/api/unknown");

        assertSecurityHeaders(response);
    }

    @Test
    void serverHeaderDoesNotRevealTheNginxVersion() {
        assertThat(api.get("/").header("Server")).hasValue("nginx");
    }

    @Test
    void healthEndpointAnswersOk() {
        final ApiResponse response = api.get("/healthz");

        assertThat(response.status()).isEqualTo(OK);
        assertThat(response.body()).isEqualTo("ok");
    }

    static Stream<String> javaScriptModules() throws IOException {
        final Path modules = system.repositoryRoot().resolve("frontend/js");
        try (Stream<Path> files = Files.list(modules)) {
            final List<String> names = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".js")).sorted().toList();
            assertThat(names).isNotEmpty();
            return names.stream();
        }
    }

    private static void assertStaticHeaders(final ApiResponse response) {
        assertSecurityHeaders(response);
        assertThat(response.header("Cache-Control")).hasValue("no-cache");
    }

    private static void assertSecurityHeaders(final ApiResponse response) {
        assertThat(response.header("Content-Security-Policy")).hasValue(CONTENT_SECURITY_POLICY);
        assertThat(response.header("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.header("Referrer-Policy")).hasValue("no-referrer");
    }
}
