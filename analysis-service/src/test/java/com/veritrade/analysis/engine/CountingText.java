package com.veritrade.analysis.engine;

/**
 * A text that counts how often the regex engine reads a character. Reads per character of input are a
 * machine-independent measure of matching cost: linear matching reads each character a bounded number
 * of times, catastrophic backtracking reads it again and again.
 */
final class CountingText implements CharSequence {

    /**
     * Character reads per input character for all 33 bundled rules together. Linear matching stays far
     * below this (about 100, 3 per rule); backtracking that is not linear grows with the input length
     * and exceeds it by orders of magnitude on 2 MB.
     */
    static final double MAX_LINEAR_READS_PER_CHAR = 500;

    private final String text;
    private long reads;

    CountingText(final String text) {
        this.text = text;
    }

    /** All rules of {@code rules} applied to {@code text}, as character reads per input character. */
    static double readsPerChar(final RuleSet rules, final RuleMatcher matcher, final String text) {
        final CountingText counting = new CountingText(text);
        for (final RiskRule rule : rules.rules()) {
            matcher.findMatches(rule, counting);
        }
        return (double) counting.reads / text.length();
    }

    @Override
    public int length() {
        return text.length();
    }

    @Override
    public char charAt(final int index) {
        reads++;
        return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
        return text.subSequence(start, end);
    }

    @Override
    public String toString() {
        return text;
    }
}
