package io.github.ghosthack.docview.fx;

import io.github.ghosthack.docview.HighlightRole;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ReaderThemeTest {

    @Test
    void presetsDifferAndCssUsesHex() {
        assertEquals("#111111", ReaderTheme.css(ReaderTheme.dark().background()));
        assertNotEquals(ReaderTheme.dark().background(), ReaderTheme.light().background());
        assertEquals("#3a6ea599", ReaderTheme.css(ReaderTheme.dark().selection()));
    }

    @Test
    void builderOverridesKeepEverythingElse() {
        ReaderTheme theme = ReaderTheme.dark().toBuilder()
                .link(Color.RED).syntax(HighlightRole.KEYWORD, Color.LIME).build();

        assertEquals(Color.RED, theme.link());
        assertEquals(Color.LIME, theme.syntax(HighlightRole.KEYWORD));
        assertEquals(ReaderTheme.dark().text(), theme.text());
        assertEquals(ReaderTheme.dark().syntax(HighlightRole.STRING),
                theme.syntax(HighlightRole.STRING));
    }

    @Test
    void everyColorIsRequired() {
        assertThrows(NullPointerException.class,
                () -> ReaderTheme.dark().toBuilder().text(null).build());
    }
}
