package com.veritrade.analysis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WhitespaceToleranceTest {

    private static final String RUN = WhitespaceTolerance.WHITESPACE_RUN;

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(delimiterString = "=>", quoteCharacter = '"', value = {
        "going concern => going" + RUN + "concern",
        "going   concern => going" + RUN + "concern",
        "\" leading and trailing \" => " + RUN + "leading" + RUN + "and" + RUN + "trailing" + RUN,
        "(?:our |the )?covenants => (?:our" + RUN + "|the" + RUN + ")?covenants",
        "file(?: protection)? => file(?:" + RUN + "protection)?",
        "cyber ?attack => cyber(?:" + RUN + ")?attack",
        "a *b => a(?:" + RUN + ")*b",
        "a {0,2}b => a(?:" + RUN + "){0,2}b",
        "(?:-| )source => (?:-|" + RUN + ")source",
        "\\[ \\] => \\[" + RUN + "\\]",
        "\\bphishing\\b => \\bphishing\\b",
        "a\\ b => a\\ b",
        "a\\sb => a\\sb",
        "\\Qa.b\\E c => \\Qa.b\\E" + RUN + "c",
        "[a-z]+ [0-9] => [a-z]+" + RUN + "[0-9]"
    })
    void rewritesEveryLiteralSpaceRunOutsideCharacterClasses(final String source, final String expected) {
        assertThat(WhitespaceTolerance.rewrite(source)).isEqualTo(expected);
        assertThat(Pattern.compile(expected)).isNotNull();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"sole[- ]source", "a[^ ]b", "a[x[ ]]b", "\\Qa b\\E", "\\Qa b"})
    void rejectsASpaceThatCanOnlyMatchOneCharacter(final String source) {
        assertThatThrownBy(() -> WhitespaceTolerance.rewrite(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("space inside");
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {" ", " ", " ", "　", "\n", "\r", "\u000B", "\f", "\u0085", " "})
    void theRunMatchesUnicodeAndVerticalWhitespace(final String whitespace) {
        assertThat(Pattern.compile(RUN).matcher(whitespace).matches()).isTrue();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"x", "-", "_", "​"})
    void theRunDoesNotMatchOtherCharacters(final String other) {
        assertThat(Pattern.compile(RUN).matcher(other).matches()).isFalse();
    }
}
