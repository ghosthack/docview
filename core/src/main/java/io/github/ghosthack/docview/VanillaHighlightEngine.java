package io.github.ghosthack.docview;

import java.util.ArrayList;
import java.util.List;

/** Adapter that preserves the application's existing lightweight tokenizer. */
final class VanillaHighlightEngine implements HighlightEngine {

    static final String ID = "vanilla";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Vanilla";
    }

    @Override
    public HighlightSession open(
            DocumentContent document, HighlightLanguage language) {
        List<DocumentKind> syntaxKinds =
                SyntaxHighlighter.syntaxKinds(document);
        return lineIndex -> {
            String line = HighlightEngines.lineText(document, lineIndex);
            List<SyntaxHighlighter.Segment> segments =
                    SyntaxHighlighter.highlightLine(
                            line, syntaxKinds.get(lineIndex), true);
            var tokens = new ArrayList<HighlightToken>(segments.size());
            int offset = 0;
            for (SyntaxHighlighter.Segment segment : segments) {
                int end = offset + segment.text().length();
                tokens.add(new HighlightToken(
                        offset, end, role(segment.style())));
                offset = end;
            }
            return List.copyOf(tokens);
        };
    }

    private static HighlightRole role(SyntaxHighlighter.Style style) {
        return HighlightRole.valueOf(style.name());
    }
}
