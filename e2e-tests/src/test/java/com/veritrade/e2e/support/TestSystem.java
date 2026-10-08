package com.veritrade.e2e.support;

import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

/** The running stack and the two ways the suite talks to it: nginx and the RabbitMQ management API. */
public final class TestSystem implements AutoCloseable {

    /** Reading the logs starts a process, so they are polled less often than the API. */
    private static final int LOG_POLL_FACTOR = 5;

    private final Path repositoryRoot;
    private final ComposeStack stack;
    private final Api api;
    private final Broker broker;

    private TestSystem(final Path repositoryRoot, final ComposeStack stack) {
        this.repositoryRoot = repositoryRoot;
        this.stack = stack;
        this.api = new Api(() -> stack.endpoint(ComposeStack.FRONTEND, ComposeStack.UI_PORT));
        this.broker = new Broker(() -> stack.endpoint(ComposeStack.RABBITMQ, ComposeStack.MANAGEMENT_PORT));
    }

    public static TestSystem start() {
        final Path root = Path.of(System.getProperty("e2e.repositoryRoot", "..")).toAbsolutePath().normalize();
        return new TestSystem(root, ComposeStack.start(root));
    }

    public Path repositoryRoot() {
        return repositoryRoot;
    }

    public ComposeStack stack() {
        return stack;
    }

    public Api api() {
        return api;
    }

    public Broker broker() {
        return broker;
    }

    public FilingRequest demoFiling() {
        return FilingRequest.demo(repositoryRoot);
    }

    /** Waits until one log line of the service contains every fragment. */
    public void awaitLogLine(final String service, final String... fragments) {
        awaitLogLine(Timeouts.MESSAGE_HANDLED, service, fragments);
    }

    public void awaitLogLine(final Duration timeout, final String service, final String... fragments) {
        await(service + " logs a line with " + Arrays.toString(fragments))
                .atMost(timeout).pollInterval(Timeouts.POLL_INTERVAL.multipliedBy(LOG_POLL_FACTOR))
                .until(() -> countLogLines(service, fragments) > 0);
    }

    /** Number of log lines of the service that contain every fragment. */
    public long countLogLines(final String service, final String... fragments) {
        return stack.logs(service).lines()
                .filter(line -> Arrays.stream(fragments).allMatch(line::contains))
                .count();
    }

    @Override
    public void close() {
        stack.close();
    }
}
