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
        Result result = filter("abc-123");

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
        String id = "x".repeat(TestProperties.MAX_CORRELATION_ID_LENGTH);

        assertThat(filter(id).header()).isEqualTo(id);
    }

    @Test
    void replacesAnIdOverTheMaximumLength() throws Exception {
        String id = "x".repeat(TestProperties.MAX_CORRELATION_ID_LENGTH + 1);

        assertGenerated(filter(id));
    }

    @Test
    void generatesAnIdWhenTheHeaderIsMissing() throws Exception {
        assertGenerated(filter(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void generatesAnIdWhenTheHeaderIsBlank(String header) throws Exception {
        assertGenerated(filter(header));
    }

    @Test
    void generatesADifferentIdForEveryRequest() throws Exception {
        assertThat(filter(null).header()).isNotEqualTo(filter(null).header());
    }

    @Test
    void clearsTheLoggingContextEvenWhenTheRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockFilterChain failing = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                throw new ServletException("boom");
            }
        });

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    private static void assertGenerated(Result result) {
        assertThat(UUID.fromString(result.header())).isNotNull();
        assertThat(result.attribute()).isEqualTo(result.header());
        assertThat(result.mdcDuringRequest()).isEqualTo(result.header());
    }

    private Result filter(String header) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader(CorrelationIds.HTTP_HEADER, header);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdc = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> mdc.set(MDC.get(CorrelationIds.MDC_KEY)));
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
        return new Result((String) request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE),
                response.getHeader(CorrelationIds.HTTP_HEADER), mdc.get());
    }

    private record Result(String attribute, String header, String mdcDuringRequest) {
    }
}
