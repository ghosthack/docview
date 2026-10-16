package io.github.ghosthack.docview;

import java.util.Locale;

/**
 * Engine-neutral semantic role for a highlighted token. Engines classify
 * ranges; the application owns their colors and rendering.
 */
public enum HighlightRole {
    PLAIN,
    COMMENT,
    STRING,
    NUMBER,
    KEYWORD,
    TYPE,
    KEY,
    TAG,
    HEADING,
    EMPHASIS,
    LINK;

    public String styleClass() {
        return name().toLowerCase(Locale.ROOT);
    }
}
