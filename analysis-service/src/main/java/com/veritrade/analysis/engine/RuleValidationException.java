package com.veritrade.analysis.engine;

import java.util.List;

/** The rules file is unusable; the message lists every problem found, so all of them can be fixed at once. */
public class RuleValidationException extends RuntimeException {

    private final List<String> errors;

    public RuleValidationException(final String source, final List<String> errors) {
        super("Invalid risk rules in " + source + ":" + System.lineSeparator() + " - "
                + String.join(System.lineSeparator() + " - ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
