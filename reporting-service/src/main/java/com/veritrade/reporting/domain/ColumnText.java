package com.veritrade.reporting.domain;

/**
 * Fits text into a VARCHAR column (sized in UTF-16 units) without splitting a surrogate pair. Events
 * validated by the listener always fit, because the contract limits count the same units; this guards
 * the service layer against text that did not pass that validation.
 */
public final class ColumnText {

    private ColumnText() {
    }

    public static String fit(final String text, final int maxUtf16Units) {
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
