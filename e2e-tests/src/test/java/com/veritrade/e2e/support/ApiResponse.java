package com.veritrade.e2e.support;

import java.net.http.HttpHeaders;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** One HTTP answer: status, headers and the body as text. */
public record ApiResponse(int status, HttpHeaders headers, String body) {

    public static final String PROBLEM_JSON = "application/problem+json";

    public JsonNode json() {
        return Json.parse(body);
    }

    public String contentType() {
        return header("Content-Type").orElse("");
    }

    public Optional<String> header(final String name) {
        return headers.firstValue(name);
    }

    public boolean isProblem(final int expectedStatus) {
        return status == expectedStatus && contentType().startsWith(PROBLEM_JSON)
                && json().path("status").asInt() == expectedStatus;
    }

    @Override
    public String toString() {
        return "HTTP " + status + " " + contentType() + " " + body;
    }
}
