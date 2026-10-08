package com.veritrade.analysis.engine;

import java.util.Set;

/**
 * Rewrites a rule pattern so that a phrase still matches when the filing breaks it with a line break,
 * a tab, several spaces or a no-break space. Every run of literal spaces outside a character class
 * becomes {@value #WHITESPACE_RUN}: one or more horizontal ({@code \h}, which includes U+00A0 and the
 * other Unicode spaces) or vertical ({@code \v}: LF, CR, VT, FF, NEL, U+2028, U+2029) whitespace
 * characters. A quantified space ({@code " ?"}) becomes an optional run. The text itself is never
 * normalised, so positions and excerpts stay offsets into the original content.
 *
 * <p>A space inside a character class or a {@code \Q...\E} quote is rejected, because it could only
 * ever match one character; write {@code (?:-| )} instead of {@code [- ]}. Escapes are copied as they
 * are: an escaped space still matches exactly one space, and {@code \s} keeps its ASCII meaning.
 */
final class WhitespaceTolerance {

    static final String WHITESPACE_RUN = "[\\h\\v]+";

    private static final Set<Character> QUANTIFIERS = Set.of('?', '*', '+', '{');
    private static final String QUOTE_END = "\\E";

    private WhitespaceTolerance() {
    }

    /** The whitespace-tolerant form of {@code regex}; throws if a space cannot be rewritten safely. */
    static String rewrite(String regex) {
        StringBuilder out = new StringBuilder(regex.length() + 16);
        int classDepth = 0;
        int i = 0;
        while (i < regex.length()) {
            char c = regex.charAt(i);
            if (c == '\\') {
                i = copyEscape(regex, i, out);
            } else if (c == ' ') {
                requireOutsideClass(classDepth);
                i = appendWhitespaceRun(regex, i, out);
            } else {
                classDepth = nextClassDepth(c, classDepth);
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int copyEscape(String regex, int backslash, StringBuilder out) {
        if (regex.startsWith("\\Q", backslash)) {
            int end = regex.indexOf(QUOTE_END, backslash);
            int stop = end < 0 ? regex.length() : end + QUOTE_END.length();
            String quoted = regex.substring(backslash, stop);
            if (quoted.indexOf(' ') >= 0) {
                throw new IllegalArgumentException("a space inside \\Q...\\E would not match line breaks; "
                        + "write the phrase without the quote");
            }
            out.append(quoted);
            return stop;
        }
        int stop = Math.min(backslash + 2, regex.length());
        out.append(regex, backslash, stop);
        return stop;
    }

    private static void requireOutsideClass(int classDepth) {
        if (classDepth > 0) {
            throw new IllegalArgumentException("a space inside a character class matches only one character; "
                    + "write (?:-| ) instead of [- ]");
        }
    }

    private static int appendWhitespaceRun(String regex, int start, StringBuilder out) {
        int end = start;
        while (end < regex.length() && regex.charAt(end) == ' ') {
            end++;
        }
        boolean quantified = end < regex.length() && QUANTIFIERS.contains(regex.charAt(end));
        out.append(quantified ? "(?:" + WHITESPACE_RUN + ")" : WHITESPACE_RUN);
        return end;
    }

    private static int nextClassDepth(char c, int classDepth) {
        if (c == '[') {
            return classDepth + 1;
        }
        if (c == ']' && classDepth > 0) {
            return classDepth - 1;
        }
        return classDepth;
    }
}
