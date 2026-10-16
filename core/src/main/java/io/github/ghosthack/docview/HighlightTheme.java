package io.github.ghosthack.docview;

/** Shared Source/Reader palette, independent of the selected engine. */
public final class HighlightTheme {

    private HighlightTheme() {
    }

    public static String color(HighlightRole role) {
        return switch (role) {
            case PLAIN -> "#d8d8d8";
            case COMMENT -> "#78906f";
            case STRING -> "#d7a86e";
            case NUMBER -> "#a8c7fa";
            case KEYWORD -> "#c792ea";
            case TYPE -> "#82aaff";
            case KEY -> "#7fdbca";
            case TAG -> "#f07178";
            case HEADING -> "#ffcb6b";
            case EMPHASIS -> "#c3e88d";
            case LINK -> "#82aaff";
        };
    }
}
