package com.veritrade.e2e.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Starts the Compose stack once for the whole run (in the root store, so every test class shares it)
 * and tears it down, with its volumes, when the run ends.
 */
public final class StackExtension implements BeforeAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(StackExtension.class);

    @Override
    public void beforeAll(final ExtensionContext context) {
        system(context);
    }

    @Override
    public boolean supportsParameter(final ParameterContext parameter, final ExtensionContext context) {
        return parameter.getParameter().getType() == TestSystem.class;
    }

    @Override
    public Object resolveParameter(final ParameterContext parameter, final ExtensionContext context) {
        return system(context);
    }

    private static TestSystem system(final ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE)
                .computeIfAbsent(TestSystem.class, key -> TestSystem.start(), TestSystem.class);
    }
}
