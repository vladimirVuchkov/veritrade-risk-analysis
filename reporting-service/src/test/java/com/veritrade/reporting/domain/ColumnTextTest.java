package com.veritrade.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ColumnTextTest {

    private static final String SMILE = "😀";

    @Test
    void keepsNullAndTextThatFits() {
        assertThat(ColumnText.fit(null, 5)).isNull();
        assertThat(ColumnText.fit("", 5)).isEmpty();
        assertThat(ColumnText.fit("abcde", 5)).isEqualTo("abcde");
    }

    @Test
    void cutsPlainTextAtTheLimit() {
        assertThat(ColumnText.fit("abcdef", 5)).isEqualTo("abcde");
    }

    @Test
    void neverSplitsASurrogatePair() {
        String text = "abcd" + SMILE;

        String fitted = ColumnText.fit(text, 5);

        assertThat(fitted).isEqualTo("abcd");
        assertThat(Character.isHighSurrogate(fitted.charAt(fitted.length() - 1))).isFalse();
    }

    @Test
    void keepsAWholePairThatEndsExactlyAtTheLimit() {
        assertThat(ColumnText.fit("abc" + SMILE + "x", 5)).isEqualTo("abc" + SMILE);
    }

    @Test
    void fitsTextMadeOnlyOfSupplementaryCharacters() {
        String fitted = ColumnText.fit(SMILE.repeat(500), 500);

        assertThat(fitted).hasSize(500).isEqualTo(SMILE.repeat(250));
        assertThat(ColumnText.fit(SMILE.repeat(500), 499)).isEqualTo(SMILE.repeat(249));
    }
}
