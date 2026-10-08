package com.veritrade.analysis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExcerptExtractorTest {

    private static final int CONTEXT = 5;

    private final ExcerptExtractor extractor = new ExcerptExtractor(CONTEXT);

    @Test
    void takesTheConfiguredContextOnBothSides() {
        String text = "0123456789MATCH0123456789";

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
        assertThat(new ExcerptExtractor(0).extract("xxMATCHxx", new Match(2, 7))).isEqualTo("MATCH");
    }

    @Test
    void neverSplitsASurrogatePairAtTheLeftEdge() {
        String text = "😀1234MATCH";

        String excerpt = extractor.extract(text, new Match(6, 11));

        assertThat(excerpt).isEqualTo("😀1234MATCH");
    }

    @Test
    void neverSplitsASurrogatePairAtTheRightEdge() {
        String text = "MATCH1234😀tail";

        String excerpt = extractor.extract(text, new Match(0, 5));

        assertThat(excerpt).isEqualTo("MATCH1234😀");
    }

    @Test
    void doesNotOverflowWithAHugeContext() {
        assertThat(new ExcerptExtractor(Integer.MAX_VALUE).extract("aMATCHb", new Match(1, 6))).isEqualTo("aMATCHb");
    }

    @Test
    void rejectsANegativeContext() {
        assertThatThrownBy(() -> new ExcerptExtractor(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
