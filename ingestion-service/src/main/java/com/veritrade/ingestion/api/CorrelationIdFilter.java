package com.veritrade.ingestion.api;

import com.veritrade.contracts.logging.CorrelationIds;
import com.veritrade.ingestion.config.IngestionProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Takes the correlation id from the request header, or generates one when it is absent, blank or too
 * long. It is put in the logging context, exposed as a request attribute and returned in the response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ATTRIBUTE = "com.veritrade.ingestion.correlationId";

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
        if (header == null || header.isBlank() || header.strip().length() > maxLength) {
            return UUID.randomUUID().toString();
        }
        return header.strip();
    }
}
