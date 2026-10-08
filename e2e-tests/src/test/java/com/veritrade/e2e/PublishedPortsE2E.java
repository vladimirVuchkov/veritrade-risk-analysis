package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.veritrade.e2e.support.ComposeStack;
import com.veritrade.e2e.support.E2ETestBase;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** W3-04: with the docker-compose.yml defaults, the UI and the management UI are published on loopback only. */
class PublishedPortsE2E extends E2ETestBase {

    private static final String LOOPBACK = "127.0.0.1:";
    /** The passwords that infra/rabbitmq/credentials-guard.sh accepts on loopback only. */
    private static final Set<String> WEAK_PASSWORDS = Set.of("", "veritrade", "change-me", "guest");
    private static final String DEFAULT_PASSWORD_WARNING = "WARNING: RabbitMQ runs with a known default password";

    @Test
    void uiIsPublishedOnLoopbackOnly() {
        assertThat(stack.binding(ComposeStack.FRONTEND, ComposeStack.UI_PORT)).startsWith(LOOPBACK);
    }

    @Test
    void managementUiIsPublishedOnLoopbackOnly() {
        assertThat(stack.binding(ComposeStack.RABBITMQ, ComposeStack.MANAGEMENT_PORT)).startsWith(LOOPBACK);
    }

    @Test
    void brokerWarnsAboutTheDefaultPasswordItWasStartedWith() {
        assumeTrue(WEAK_PASSWORDS.contains(System.getenv().getOrDefault("RABBITMQ_PASSWORD", "veritrade")),
                "the suite runs with a strong RABBITMQ_PASSWORD");
        assertThat(stack.logs(ComposeStack.RABBITMQ)).contains(DEFAULT_PASSWORD_WARNING);
    }
}
