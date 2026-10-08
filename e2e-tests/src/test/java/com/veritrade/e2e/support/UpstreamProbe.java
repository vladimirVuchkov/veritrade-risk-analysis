package com.veritrade.e2e.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Sends the same request through nginx every {@link Timeouts#POLL_INTERVAL} in the background and
 * records how long each answer took. Used across a {@code docker compose stop}, so some requests reach
 * nginx while it still holds the address of the stopped container.
 */
public final class UpstreamProbe implements AutoCloseable {

    /** One request: when it started ({@link System#nanoTime()}), how long it took, and its answer or error. */
    public record Result(long startedNanos, Duration elapsed, ApiResponse response, RuntimeException error) {

        public boolean isProblem(int status) {
            return response != null && response.isProblem(status);
        }

        @Override
        public String toString() {
            return elapsed.toMillis() + " ms: " + (response != null ? response.status() : error);
        }
    }

    private final Supplier<ApiResponse> request;
    private final List<Result> results = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private UpstreamProbe(Supplier<ApiResponse> request) {
        this.request = request;
    }

    public static UpstreamProbe start(Supplier<ApiResponse> request) {
        UpstreamProbe probe = new UpstreamProbe(request);
        probe.scheduler.scheduleWithFixedDelay(probe::probeOnce, 0, Timeouts.POLL_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
        return probe;
    }

    /** True once a request that started at or after {@code nanos} has an answer. */
    public boolean hasResultStartedAfter(long nanos) {
        return results.stream().anyMatch(result -> result.startedNanos() - nanos >= 0);
    }

    /** Stops probing, waits for the request in flight and returns every result. */
    public List<Result> finish() {
        close();
        return List.copyOf(results);
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(Timeouts.HTTP_REQUEST.multipliedBy(2).toMillis(), TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    private void probeOnce() {
        long started = System.nanoTime();
        ApiResponse response = null;
        RuntimeException error = null;
        try {
            response = request.get();
        } catch (RuntimeException e) {
            error = e;
        }
        results.add(new Result(started, Duration.ofNanos(System.nanoTime() - started), response, error));
    }
}
