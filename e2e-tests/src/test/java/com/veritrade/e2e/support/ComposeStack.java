package com.veritrade.e2e.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The repository's docker-compose.yml, run through the docker compose CLI under its own project name.
 * Both published ports are random host ports, so the suite never collides with another stack on
 * 8080 or 15672. They are bound to the docker-compose.yml default address (loopback). The CLI (rather than a Testcontainers wrapper) is used because the scenarios stop and
 * start single services, read their logs and look up ports again after a container restart.
 */
public final class ComposeStack implements AutoCloseable {

    public static final String RABBITMQ = "rabbitmq";
    public static final String INGESTION = "ingestion-service";
    public static final String ANALYSIS = "analysis-service";
    public static final String REPORTING = "reporting-service";
    public static final String FRONTEND = "frontend";
    public static final List<String> JAVA_SERVICES = List.of(INGESTION, ANALYSIS, REPORTING);

    public static final int UI_PORT = 8080;
    public static final int MANAGEMENT_PORT = 15672;

    private static final String PROJECT_PREFIX = "veritrade-e2e-";
    private static final int PROJECT_SUFFIX_BYTES = 4;
    private static final String RANDOM_HOST_PORT = "0";
    private static final List<String> WILDCARD_ADDRESSES = List.of("0.0.0.0", "[::]", "::");
    /** Removed from the environment, so the stack is published on the docker-compose.yml default (loopback). */
    private static final String BIND_ADDRESS_VARIABLE = "BIND_ADDRESS";

    private final Path repositoryRoot;
    private final String project;
    private final boolean keepRunning;

    private ComposeStack(final Path repositoryRoot, final String project, final boolean keepRunning) {
        this.repositoryRoot = repositoryRoot;
        this.project = project;
        this.keepRunning = keepRunning;
    }

    /**
     * Builds and starts the stack. {@code -De2e.project=NAME} reuses a named project and
     * {@code -De2e.keepStack=true} leaves it running afterwards (for debugging); by default every run
     * gets a fresh project that is removed with its volumes at the end.
     */
    public static ComposeStack start(final Path repositoryRoot) {
        final String project = System.getProperty("e2e.project", randomProjectName());
        final ComposeStack stack = new ComposeStack(repositoryRoot, project, Boolean.getBoolean("e2e.keepStack"));
        stack.compose(Timeouts.STACK_START, "up", "--build", "-d", "--wait",
                "--wait-timeout", seconds(Timeouts.STACK_START));
        return stack;
    }

    public String project() {
        return project;
    }

    public void stop(final String service) {
        compose(Timeouts.COMPOSE_COMMAND, "stop", service);
    }

    /** Starts a stopped service and waits until its health check passes. */
    public void start(final String service) {
        compose(Timeouts.SERVICE_START, "up", "-d", "--no-deps", "--no-build", "--wait",
                "--wait-timeout", seconds(Timeouts.SERVICE_START), service);
    }

    /** Starts a service without waiting for its health check (it cannot be healthy while the broker is down). */
    public void startWithoutWaiting(final String service) {
        compose(Timeouts.COMPOSE_COMMAND, "up", "-d", "--no-deps", "--no-build", service);
    }

    /** Brings every service back up; a no-op when all of them are healthy. */
    public void ensureAllRunning() {
        compose(Timeouts.SERVICE_START, "up", "-d", "--no-build", "--wait",
                "--wait-timeout", seconds(Timeouts.SERVICE_START));
    }

    public String logs(final String service) {
        return compose(Timeouts.COMPOSE_COMMAND, "logs", "--no-color", service);
    }

    /** The host address of a published container port; it changes when the container is recreated or restarted. */
    public URI endpoint(final String service, final int containerPort) {
        final String binding = binding(service, containerPort);
        final int separator = binding.lastIndexOf(':');
        final String host = binding.substring(0, separator);
        final String reachableHost = WILDCARD_ADDRESSES.contains(host) ? "localhost" : host;
        return URI.create("http://" + reachableHost + ":" + binding.substring(separator + 1));
    }

    /** What {@code docker compose port} prints for a published container port, for example {@code 127.0.0.1:49153}. */
    public String binding(final String service, final int containerPort) {
        return compose(Timeouts.COMPOSE_COMMAND, "port", service, String.valueOf(containerPort)).strip();
    }

    @Override
    public void close() {
        if (!keepRunning) {
            compose(Timeouts.COMPOSE_COMMAND, "down", "-v", "--remove-orphans");
        }
    }

    private String compose(final Duration timeout, final String... arguments) {
        final List<String> command = new ArrayList<>(List.of("docker", "compose", "-p", project,
                "-f", repositoryRoot.resolve("docker-compose.yml").toString()));
        command.addAll(List.of(arguments));
        return run(command, timeout);
    }

    private String run(final List<String> command, final Duration timeout) {
        final ProcessBuilder builder = new ProcessBuilder(command).directory(repositoryRoot.toFile()).redirectErrorStream(true);
        builder.environment().putAll(Map.of("UI_PORT", RANDOM_HOST_PORT, "RABBITMQ_MANAGEMENT_PORT", RANDOM_HOST_PORT));
        builder.environment().remove(BIND_ADDRESS_VARIABLE);
        try {
            final Process process = builder.start();
            final CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Timed out after " + timeout + ": " + String.join(" ", command));
            }
            final String text = output.join();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Failed (exit " + process.exitValue() + "): "
                        + String.join(" ", command) + System.lineSeparator() + text);
            }
            return text;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted: " + String.join(" ", command), e);
        }
    }

    private static String readAll(final InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String randomProjectName() {
        final byte[] suffix = new byte[PROJECT_SUFFIX_BYTES];
        ThreadLocalRandom.current().nextBytes(suffix);
        return PROJECT_PREFIX + HexFormat.of().formatHex(suffix);
    }

    private static String seconds(final Duration duration) {
        return String.valueOf(duration.toSeconds());
    }
}
