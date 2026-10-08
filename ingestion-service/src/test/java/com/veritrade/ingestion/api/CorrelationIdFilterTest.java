package com.veritrade.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veritrade.contracts.logging.CorrelationIds;
import com.veritrade.ingestion.support.TestProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter(TestProperties.defaults());

    @Test
    void keepsTheCorrelationIdOfTheRequest() throws Exception {
        final Result result = filter("abc-123");

        assertThat(result.attribute()).isEqualTo("abc-123");
        assertThat(result.header()).isEqualTo("abc-123");
        assertThat(result.mdcDuringRequest()).isEqualTo("abc-123");
    }

    @Test
    void stripsSurroundingWhitespace() throws Exception {
        assertThat(filter("  abc  ").header()).isEqualTo("abc");
    }

    @Test
    void keepsAnIdOfExactlyTheMaximumLength() throws Exception {
        final String id = "x".repeat(TestProperties.MAX_CORRELATION_ID_LENGTH);

        assertThat(filter(id).header()).isEqualTo(id);
    }

    @Test
    void replacesAnIdOverTheMaximumLength() throws Exception {
        final String id = "x".repeat(TestProperties.MAX_CORRELATION_ID_LENGTH + 1);

        assertGenerated(filter(id));
    }

    @Test
    void keepsEveryAllowedCharacterClass() throws Exception {
        final String id = "AZaz09._:-" + "x".repeat(TestProperties.MAX_CORRELATION_ID_LENGTH - 10);

        assertThat(filter(id).header()).isEqualTo(id);
    }

    /**
     * 64 "é" are 128 characters once Tomcat decodes the raw UTF-8 bytes as ISO-8859-1, but 256 bytes
     * in UTF-8: over the 255-byte AMQP short string that carries the correlation id (review W3-01).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "\u00c3\u00a9\u00c3\u00a9\u00c3\u00a9", "\u00e9t\u00e9", "\u6ce8\u6587", "\ud83d\udcc8",
            "abc\u00a0def", "caf\u00e9-123"})
    void replacesANonAsciiId(final String id) throws Exception {
        assertGenerated(filter(id));
    }

    @Test
    void replacesTheIdThatBlockedTheOutbox() throws Exception {
        final String decodedByTomcat = new String("\u00e9".repeat(64).getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);

        assertThat(decodedByTomcat).hasSize(TestProperties.MAX_CORRELATION_ID_LENGTH);
        assertGenerated(filter(decodedByTomcat));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc\u0000def", "abc\u0007def", "abc\u001bdef", "abc\u007fdef", "abc\ndef", "abc\rdef",
            "abc\tdef"})
    void replacesAnIdWithControlCharacters(final String id) throws Exception {
        assertGenerated(filter(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc def", "abc  def", " a b "})
    void replacesAnIdWithInnerSpaces(final String id) throws Exception {
        assertGenerated(filter(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc/def", "a;b", "a,b", "<script>", "a\"b", "a=b", "a+b", "{id}", "a%20b"})
    void replacesAnIdWithOtherPunctuation(final String id) throws Exception {
        assertGenerated(filter(id));
    }

    @Test
    void generatesAnIdWhenTheHeaderIsMissing() throws Exception {
        assertGenerated(filter(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void generatesAnIdWhenTheHeaderIsBlank(final String header) throws Exception {
        assertGenerated(filter(header));
    }

    @Test
    void generatesADifferentIdForEveryRequest() throws Exception {
        assertThat(filter(null).header()).isNotEqualTo(filter(null).header());
    }

    @Test
    void clearsTheLoggingContextEvenWhenTheRequestFails() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockFilterChain failing = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(final HttpServletRequest req, final HttpServletResponse res) throws ServletException {
                throw new ServletException("boom");
            }
        });

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    private static void assertGenerated(final Result result) {
        assertThat(UUID.fromString(result.header())).isNotNull();
        assertThat(result.attribute()).isEqualTo(result.header());
        assertThat(result.mdcDuringRequest()).isEqualTo(result.header());
    }

    private Result filter(final String header) throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader(CorrelationIds.HTTP_HEADER, header);
        }
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final AtomicReference<String> mdc = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> mdc.set(MDC.get(CorrelationIds.MDC_KEY)));
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
        return new Result((String) request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE),
                response.getHeader(CorrelationIds.HTTP_HEADER), mdc.get());
    }

    private record Result(String attribute, String header, String mdcDuringRequest) {
    }
}
