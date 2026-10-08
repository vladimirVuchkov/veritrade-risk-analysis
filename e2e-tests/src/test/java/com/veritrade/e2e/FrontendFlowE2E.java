package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.Timeouts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The frontend's flow (frontend/js/api.js and polling.js, unchanged) against the real stack: runs
 * src/test/node/frontend-flow.test.mjs with Node, which needs no npm packages.
 */
class FrontendFlowE2E extends E2ETestBase {

    @Test
    void frontendModulesDriveSubmitPollAndReportAgainstTheRealStack() throws IOException, InterruptedException {
        final Path script = system.repositoryRoot().resolve("e2e-tests/src/test/node/frontend-flow.test.mjs");
        final ProcessBuilder builder = new ProcessBuilder("node", "--test", script.toString())
                .directory(system.repositoryRoot().toFile())
                .redirectErrorStream(true);
        builder.environment().put("E2E_BASE_URL", api.baseUri().toString());
        builder.environment().put("E2E_REPOSITORY_ROOT", system.repositoryRoot().toString());

        final Process node = builder.start();
        final boolean finished = node.waitFor(Timeouts.NODE_FLOW.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            node.destroyForcibly();
        }
        final String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(finished).as("node finished within %s", Timeouts.NODE_FLOW).isTrue();
        assertThat(node.exitValue()).as(output).isZero();
        assertThat(output).contains("# fail 0");
    }
}
