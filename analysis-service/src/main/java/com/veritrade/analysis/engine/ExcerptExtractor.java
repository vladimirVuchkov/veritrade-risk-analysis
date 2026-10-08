package com.veritrade.analysis.engine;

/**
 * Cuts the context around a match: up to {@code contextChars} characters on each side, fewer at the
 * start or end of the text, never splitting a surrogate pair. The whole excerpt is at most
 * {@code maxExcerptChars} UTF-16 code units: for a long match the context shrinks to fit.
 */
public final class ExcerptExtractor {

    /** Each side may grow by one code unit so that it does not split a surrogate pair. */
    private static final int SURROGATE_ALLOWANCE = 1;

    private final int contextChars;
    private final int maxExcerptChars;

    public ExcerptExtractor(int contextChars, int maxExcerptChars) {
        if (contextChars < 0) {
            throw new IllegalArgumentException("contextChars must be >= 0, was " + contextChars);
        }
        if (maxExcerptChars < 1) {
            throw new IllegalArgumentException("maxExcerptChars must be >= 1, was " + maxExcerptChars);
        }
        this.contextChars = contextChars;
        this.maxExcerptChars = maxExcerptChars;
    }

    /** The excerpt; requires a match no longer than {@code maxExcerptChars}. */
    public String extract(String text, Match match) {
        int matchLength = match.end() - match.start();
        if (matchLength > maxExcerptChars) {
            throw new IllegalArgumentException("A match of " + matchLength
                    + " characters does not fit into an excerpt of " + maxExcerptChars);
        }
        int context = Math.max(0, Math.min(contextChars, (maxExcerptChars - matchLength) / 2 - SURROGATE_ALLOWANCE));
        int from = TextBounds.safeStart(text, Math.max(0, match.start() - context));
        int to = TextBounds.safeEnd(text, match.end() + Math.min(context, text.length() - match.end()));
        return text.substring(from, to);
    }
}
