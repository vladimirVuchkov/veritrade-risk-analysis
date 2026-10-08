package com.veritrade.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The REST API, reached only through nginx. */
public final class Api {

    public static final String FILINGS = "/api/filings";
    public static final String REPORTS = "/api/reports";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String JSON = "application/json";
    private static final Set<String> FINAL_STATUSES = Set.of("COMPLETED", "FAILED");
    private static final int OK = 200;
    private static final int ACCEPTED = 202;

    private final URI baseUri;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Timeouts.HTTP_REQUEST).build();

    public Api(URI baseUri) {
        this.baseUri = baseUri;
    }

    public URI baseUri() {
        return baseUri;
    }

    public ApiResponse submit(FilingRequest filing) {
        return submit(filing.toJson(), Map.of());
    }

    public ApiResponse submit(String json, Map<String, String> headers) {
        return post(FILINGS, BodyPublishers.ofString(json), JSON, headers);
    }

    /** Submits a filing that must be accepted and returns its id. */
    public UUID submitAccepted(FilingRequest filing) {
        return submitAccepted(filing, Map.of());
    }

    public UUID submitAccepted(FilingRequest filing, Map<String, String> headers) {
        ApiResponse response = submit(filing.toJson(), headers);
        assertThat(response.status()).as("submit %s", response).isEqualTo(ACCEPTED);
        return UUID.fromString(response.json().path("filingId").asString());
    }

    public ApiResponse post(String path, BodyPublisher body, String contentType, Map<String, String> headers) {
        HttpRequest.Builder request = request(path).POST(body);
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        headers.forEach(request::header);
        return send(request.build());
    }

    public ApiResponse get(String path) {
        return send(request(path).GET().header("Accept", JSON).build());
    }

    public ApiResponse filing(UUID filingId) {
        return get(FILINGS + "/" + filingId);
    }

    public ApiResponse report(UUID filingId) {
        return get(REPORTS + "/" + filingId);
    }

    public String status(UUID filingId) {
        ApiResponse response = filing(filingId);
        return response.status() == OK ? response.json().path("status").asString() : "HTTP " + response.status();
    }

    /** Waits until the filing has the expected status; fails at once on a different final status. */
    public JsonNode awaitStatus(UUID filingId, String expected) {
        return awaitStatus(filingId, expected, Timeouts.PROCESSING);
    }

    public JsonNode awaitStatus(UUID filingId, String expected, Duration timeout) {
        await("filing " + filingId + " " + expected).atMost(timeout).pollInterval(Timeouts.POLL_INTERVAL)
                .ignoreExceptionsInstanceOf(UncheckedIOException.class)
                .until(() -> reachedOrFailFast(filingId, expected));
        return filing(filingId).json();
    }

    /** Waits until the report answers 200 and returns it. */
    public JsonNode awaitReport(UUID filingId) {
        return awaitReport(filingId, Timeouts.PROCESSING);
    }

    public JsonNode awaitReport(UUID filingId, Duration timeout) {
        return await("report of " + filingId).atMost(timeout).pollInterval(Timeouts.POLL_INTERVAL)
                .ignoreExceptionsInstanceOf(UncheckedIOException.class)
                .until(() -> report(filingId), response -> response.status() == OK)
                .json();
    }

    private boolean reachedOrFailFast(UUID filingId, String expected) {
        String actual = status(filingId);
        if (FINAL_STATUSES.contains(actual) && !actual.equals(expected)) {
            throw new AssertionError("filing " + filingId + " is " + actual + ", expected " + expected);
        }
        return actual.equals(expected);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Timeouts.HTTP_REQUEST);
    }

    private ApiResponse send(HttpRequest request) {
        try {
            var response = client.send(request, BodyHandlers.ofString());
            return new ApiResponse(response.statusCode(), response.headers(), response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
