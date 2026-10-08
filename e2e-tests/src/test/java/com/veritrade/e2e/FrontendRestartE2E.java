package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.e2e.support.Api;
import com.veritrade.e2e.support.ComposeStack;
import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.FilingRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * nginx restarted: a restarted container can get a new random host port (the suite publishes port 0),
 * so the client must find nginx again instead of failing every later request with a refused connection.
 * Compose restarts nginx on its own when it brings a dependency back, as the CI runner showed.
 */
class FrontendRestartE2E extends E2ETestBase {

    private static final int OK = 200;

    @Test
    void apiIsReachableAgainAfterNginxRestarts() {
        stack.stop(ComposeStack.FRONTEND);
        stack.start(ComposeStack.FRONTEND);

        assertThat(api.get(Api.FILINGS + "?limit=1").status()).isEqualTo(OK);
        final UUID filingId = api.submitAccepted(FilingRequest.noRisk("After nginx restart " + UUID.randomUUID()));
        api.awaitStatus(filingId, "COMPLETED");
    }
}
