package com.veritrade.ingestion.api;

import com.veritrade.contracts.logging.CorrelationIds;
import com.veritrade.ingestion.config.IngestionProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Takes the correlation id from the request header when it is a strict token: 1 to {@code maxLength}
 * characters from {@code [A-Za-z0-9._:-]}, after surrounding whitespace is stripped. Any other value
 * (absent, blank, too long, non-ASCII, control characters, inner spaces) is replaced with a generated
 * UUID; the request is never rejected for it. The id is put in the logging context, exposed as a request
 * attribute and returned in the response.
 *
 * <p>The token is ASCII, so it is at most {@code maxLength} bytes in UTF-8 and always fits the AMQP
 * {@code correlationId} short string (255 bytes), the {@code outbox.correlation_id} column and a log line.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ATTRIBUTE = "com.veritrade.ingestion.correlationId";

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._:-]+");

    private final int maxLength;

    public CorrelationIdFilter(IngestionProperties properties) {
        this.maxLength = properties.correlationId().maxLength();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(CorrelationIds.HTTP_HEADER));
        request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(CorrelationIds.HTTP_HEADER, correlationId);
        MDC.put(CorrelationIds.MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }

    private String resolve(String header) {
        String candidate = header == null ? "" : header.strip();
        if (candidate.isEmpty() || candidate.length() > maxLength || !TOKEN.matcher(candidate).matches()) {
            return UUID.randomUUID().toString();
        }
        return candidate;
    }
}
