package com.veritrade.analysis.engine;

/**
 * Cuts the context around a match: up to {@code contextChars} characters on each side, fewer at the
 * start or end of the text, never splitting a surrogate pair.
 */
public final class ExcerptExtractor {

    private final int contextChars;

    public ExcerptExtractor(int contextChars) {
        if (contextChars < 0) {
            throw new IllegalArgumentException("contextChars must be >= 0, was " + contextChars);
        }
        this.contextChars = contextChars;
    }

    public String extract(String text, Match match) {
        int from = TextBounds.safeStart(text, Math.max(0, match.start() - contextChars));
        int to = TextBounds.safeEnd(text, match.end() + Math.min(contextChars, text.length() - match.end()));
        return text.substring(from, to);
    }
}
