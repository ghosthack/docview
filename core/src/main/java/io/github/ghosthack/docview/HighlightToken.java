package io.github.ghosthack.docview;

import java.util.Objects;

/**
 * One half-open, line-relative token range. Sessions must return ordered,
 * non-overlapping tokens that cover the complete physical line.
 */
public record HighlightToken(int start, int end, HighlightRole role) {

    public HighlightToken {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException(
                    "Invalid highlight range " + start + ".." + end);
        }
        Objects.requireNonNull(role, "role");
    }
}
