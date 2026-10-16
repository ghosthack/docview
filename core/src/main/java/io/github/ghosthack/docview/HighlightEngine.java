package io.github.ghosthack.docview;

/**
 * Pluggable syntax-highlighting provider. Implementations may be discovered
 * through {@link java.util.ServiceLoader} or registered at runtime.
 */
public interface HighlightEngine {

    String id();

    String displayName();

    default boolean available() {
        return true;
    }

    default boolean supports(HighlightLanguage language) {
        return true;
    }

    /**
     * Higher-priority engines win Automatic selection. Vanilla deliberately
     * uses zero so a future TextMate/Tree-sitter provider can supersede it.
     */
    default int automaticPriority() {
        return 0;
    }

    HighlightSession open(
            DocumentContent document, HighlightLanguage language);
}
