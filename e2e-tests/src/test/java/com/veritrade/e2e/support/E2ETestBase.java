package com.veritrade.e2e.support;

import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;

/** Gives every end-to-end test class the shared stack. Test classes run one after the other. */
@ExtendWith(StackExtension.class)
public abstract class E2ETestBase {

    protected static TestSystem system;
    protected static Api api;
    protected static Broker broker;
    protected static ComposeStack stack;

    @BeforeAll
    static void attach(final TestSystem testSystem) {
        system = testSystem;
        api = testSystem.api();
        broker = testSystem.broker();
        stack = testSystem.stack();
    }

    /** A correlation id that names the test, so its log lines are easy to find. */
    protected static String correlationId(final String scenario) {
        return "e2e-" + scenario + "-" + UUID.randomUUID();
    }
}
