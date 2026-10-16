package io.github.ghosthack.docview;

/**
 * Typography for a Reader: prose and code font families (resolved with the
 * Reader stylesheet's fallbacks when not installed), size in px, bold/italic,
 * line spacing (a multiple of the font size), letter spacing in px, wrapping
 * and tab size. Used by {@link SafeMarkupRenderer} pages and the native views.
 */
public record ReaderStyle(
        String readerFontFamily,
        String codeFontFamily,
        double fontSize,
        boolean bold,
        boolean italic,
        double lineSpacing,
        double letterSpacing,
        boolean wrap,
        int tabSize
) {

    /**
     * A 15 px serif reading style with 1.35 line spacing, wrapping and a
     * tab size of 4; families fall back like the Reader stylesheet.
     */
    public static ReaderStyle defaults() {
        return new ReaderStyle("Iowan Old Style", "SF Mono", 15, false, false,
                1.35, 0, true, 4);
    }
    public ReaderStyle {
        if (readerFontFamily == null || readerFontFamily.isBlank()) {
            readerFontFamily = "serif";
        }
        if (codeFontFamily == null || codeFontFamily.isBlank()) {
            codeFontFamily = "monospace";
        }
        fontSize = Math.max(8, Math.min(72, fontSize));
        lineSpacing = Math.max(0.8, Math.min(3.0, lineSpacing));
        letterSpacing = Math.max(-2, Math.min(12, letterSpacing));
        tabSize = Math.max(1, Math.min(16, tabSize));
    }
}
