package com.veritrade.analysis.engine;

/** Moves cut points so that a substring never splits a UTF-16 surrogate pair (emoji, rare CJK). */
final class TextBounds {

    private TextBounds() {
    }

    /** A start index that does not begin in the middle of a surrogate pair; moves left if needed. */
    static int safeStart(CharSequence text, int index) {
        int clamped = Math.clamp(index, 0, text.length());
        if (clamped > 0 && clamped < text.length() && Character.isLowSurrogate(text.charAt(clamped))
                && Character.isHighSurrogate(text.charAt(clamped - 1))) {
            return clamped - 1;
        }
        return clamped;
    }

    /** An end index that does not end in the middle of a surrogate pair; moves right if needed. */
    static int safeEnd(CharSequence text, int index) {
        int clamped = Math.clamp(index, 0, text.length());
        if (clamped > 0 && clamped < text.length() && Character.isHighSurrogate(text.charAt(clamped - 1))
                && Character.isLowSurrogate(text.charAt(clamped))) {
            return clamped + 1;
        }
        return clamped;
    }
}
