package com.veritrade.analysis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExcerptExtractorTest {

    private static final int CONTEXT = 5;
    private static final int MAX = 1000;
    private static final int MAX_MATCH = 500;
    private static final String EMOJI = "\uD83D\uDE00";

    private final ExcerptExtractor extractor = new ExcerptExtractor(CONTEXT, MAX);

    @Test
    void takesTheConfiguredContextOnBothSides() {
        final String text = "0123456789MATCH0123456789";

        assertThat(extractor.extract(text, new Match(10, 15))).isEqualTo("56789MATCH01234");
    }

    @Test
    void hasNoLeftContextForAMatchAtPositionZero() {
        assertThat(extractor.extract("MATCH0123456789", new Match(0, 5))).isEqualTo("MATCH01234");
    }

    @Test
    void hasNoRightContextForAMatchAtTheEndOfTheText() {
        assertThat(extractor.extract("0123456789MATCH", new Match(10, 15))).isEqualTo("56789MATCH");
    }

    @Test
    void returnsTheWholeTextWhenItIsShorterThanTheContext() {
        assertThat(extractor.extract("aMATCHb", new Match(1, 6))).isEqualTo("aMATCHb");
    }

    @Test
    void returnsOnlyTheMatchWithZeroContext() {
        assertThat(new ExcerptExtractor(0, MAX).extract("xxMATCHxx", new Match(2, 7))).isEqualTo("MATCH");
    }

    @Test
    void neverSplitsASurrogatePairAtTheLeftEdge() {
        final String text = "😀1234MATCH";

        final String excerpt = extractor.extract(text, new Match(6, 11));

        assertThat(excerpt).isEqualTo("😀1234MATCH");
    }

    @Test
    void neverSplitsASurrogatePairAtTheRightEdge() {
        final String text = "MATCH1234😀tail";

        final String excerpt = extractor.extract(text, new Match(0, 5));

        assertThat(excerpt).isEqualTo("MATCH1234😀");
    }

    @Test
    void doesNotOverflowWithAHugeContext() {
        assertThat(new ExcerptExtractor(Integer.MAX_VALUE, MAX).extract("aMATCHb", new Match(1, 6))).isEqualTo("aMATCHb");
    }

    @Test
    void rejectsANegativeContext() {
        assertThatThrownBy(() -> new ExcerptExtractor(-1, MAX)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANonPositiveMaximum() {
        assertThatThrownBy(() -> new ExcerptExtractor(CONTEXT, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shrinksTheContextSoThatALongMatchFitsTheMaximum() {
        final String text = "c".repeat(MAX) + "m".repeat(MAX_MATCH) + "c".repeat(MAX);

        final String excerpt = new ExcerptExtractor(MAX, MAX).extract(text, new Match(MAX, MAX + MAX_MATCH));

        assertThat(excerpt).hasSizeLessThanOrEqualTo(MAX).contains("m".repeat(MAX_MATCH));
        assertThat(excerpt).startsWith("c").endsWith("c");
    }

    @Test
    void returnsOnlyAMatchOfExactlyTheMaximumLength() {
        final String text = "c".repeat(10) + "m".repeat(MAX) + "c".repeat(10);

        assertThat(new ExcerptExtractor(CONTEXT, MAX).extract(text, new Match(10, 10 + MAX))).isEqualTo("m".repeat(MAX));
    }

    @Test
    void rejectsAMatchLongerThanTheMaximum() {
        assertThatThrownBy(() -> new ExcerptExtractor(CONTEXT, 3).extract("abcd", new Match(0, 4)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "match of {0} code units")
    @ValueSource(ints = {1, 2, MAX_MATCH - 1, MAX_MATCH, MAX - 2, MAX - 1, MAX})
    void neverExceedsTheMaximumEvenBetweenSurrogatePairs(final int matchLength) {
        final String side = EMOJI.repeat(MAX);
        final String text = side + "m".repeat(matchLength) + side;
        final Match match = new Match(side.length(), side.length() + matchLength);

        final String excerpt = new ExcerptExtractor(MAX, MAX).extract(text, match);

        assertThat(excerpt).hasSizeLessThanOrEqualTo(MAX).contains("m".repeat(matchLength));
        assertThat(Character.isLowSurrogate(excerpt.charAt(0))).isFalse();
        assertThat(Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 1))).isFalse();
    }

    @ParameterizedTest(name = "shifted by {0}")
    @ValueSource(ints = {0, 1})
    void neverExceedsTheMaximumWhenTheContextEndsInsideASurrogatePair(final int shift) {
        final String text = "x".repeat(shift) + EMOJI.repeat(MAX) + "m".repeat(MAX_MATCH) + "x".repeat(shift) + EMOJI.repeat(MAX);
        final int start = shift + 2 * MAX;

        final String excerpt = new ExcerptExtractor(MAX, MAX).extract(text, new Match(start, start + MAX_MATCH));

        assertThat(excerpt).hasSizeLessThanOrEqualTo(MAX);
    }
}
