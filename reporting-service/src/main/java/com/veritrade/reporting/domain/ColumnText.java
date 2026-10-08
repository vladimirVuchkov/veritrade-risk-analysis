package com.veritrade.reporting.domain;

/**
 * Fits text into a VARCHAR column. The contract limits count code points, the database counts
 * UTF-16 units, so text that is valid by contract (for example many emoji) can still be too long
 * for its column. Such text is shortened without splitting a surrogate pair.
 */
public final class ColumnText {

    private ColumnText() {
    }

    public static String fit(String text, int maxUtf16Units) {
        if (text == null || text.length() <= maxUtf16Units) {
            return text;
        }
        int end = maxUtf16Units;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
