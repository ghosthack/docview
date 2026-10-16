package io.github.ghosthack.docview;

import java.util.List;

/** Built-in no-highlighting engine and universal safe fallback. */
final class PlainHighlightEngine implements HighlightEngine {

    static final String ID = "none";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "None";
    }

    @Override
    public int automaticPriority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public HighlightSession open(
            DocumentContent document, HighlightLanguage language) {
        return lineIndex -> {
            String line = HighlightEngines.lineText(document, lineIndex);
            return HighlightEngines.plainTokens(line);
        };
    }
}
