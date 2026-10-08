package com.veritrade.analysis.engine;

/** A matched region of the text: {@code start} inclusive, {@code end} exclusive, in UTF-16 chars. */
public record Match(int start, int end) {

    public Match {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Invalid match bounds [" + start + ", " + end + ")");
        }
    }

    boolean overlaps(Match other) {
        return start < other.end && other.start < end;
    }
}
