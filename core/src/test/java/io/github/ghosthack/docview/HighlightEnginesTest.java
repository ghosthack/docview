package io.github.ghosthack.docview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class HighlightEnginesTest {

    @Test
    void vanillaAndNoneShareTheEngineNeutralTokenContract() {
        DocumentContent document =
                content(DocumentKind.JAVA, "public class Example {}");

        try (HighlightSession vanilla =
                        HighlightEngines.open(
                                HighlightEngines.VANILLA, document);
                HighlightSession none =
                        HighlightEngines.open(
                                HighlightEngines.NONE, document)) {
            List<HighlightToken> vanillaTokens =
                    vanilla.tokensForLine(0);
            assertEquals(0, vanillaTokens.getFirst().start());
            assertEquals(document.text().length(),
                    vanillaTokens.getLast().end());
            assertTrue(vanillaTokens.stream().anyMatch(token ->
                    token.role() == HighlightRole.KEYWORD));

            assertEquals(List.of(new HighlightToken(
                            0, document.text().length(),
                            HighlightRole.PLAIN)),
                    none.tokensForLine(0));
        }
        // TextMate comes from the optional docview-textmate module only.
        assertFalse(HighlightEngines.choices().stream().anyMatch(
                choice -> choice.id().equals(
                        HighlightEngines.TEXTMATE)));
    }

    @Test
    void exactLanguageIdentityDoesNotCollapseScriptFamilies() {
        HighlightLanguage javascript = HighlightLanguage.forDocument(
                content(Path.of("app.js"), DocumentKind.SCRIPT, ""));
        HighlightLanguage python = HighlightLanguage.forDocument(
                content(Path.of("tool.py"), DocumentKind.SCRIPT, ""));
        HighlightLanguage react = HighlightLanguage.forDocument(
                content(Path.of("view.tsx"), DocumentKind.SCRIPT, ""));
        HighlightLanguage zshrc = HighlightLanguage.forDocument(
                content(Path.of(".zshrc"), DocumentKind.SHELL, ""));
        HighlightLanguage tcl = HighlightLanguage.forDocument(
                content(Path.of("browser.tcl"), DocumentKind.TCL, ""));
        HighlightLanguage fencedRuby =
                HighlightLanguage.forMarkdownFence("```ruby");
        HighlightLanguage fencedTcl =
                HighlightLanguage.forMarkdownFence("```tcl");

        assertEquals("javascript", javascript.id());
        assertEquals("python", python.id());
        assertEquals("typescriptreact", react.id());
        assertEquals("shell", zshrc.id());
        assertEquals("tcl", tcl.id());
        assertEquals(DocumentKind.SCRIPT, javascript.kind());
        assertEquals(DocumentKind.SCRIPT, python.kind());
        assertEquals(DocumentKind.TCL, tcl.kind());
        assertEquals("ruby", fencedRuby.id());
        assertEquals(DocumentKind.RUBY, fencedRuby.kind());
        assertEquals("tcl", fencedTcl.id());
        assertEquals(DocumentKind.TCL, fencedTcl.kind());
    }

    @Test
    void automaticUsesTclAwareVanillaFallback() {
        DocumentContent tcl = content(
                Path.of("browser.tcl"), DocumentKind.TCL,
                "proc greet {name} { puts $name } ; # greeting");

        try (HighlightSession session =
                     HighlightEngines.open(
                             HighlightEngines.AUTOMATIC, tcl)) {
            List<HighlightToken> tokens =
                    session.tokensForLine(0);
            assertTrue(tokens.stream().anyMatch(token ->
                    token.role() == HighlightRole.KEYWORD));
            assertTrue(tokens.stream().anyMatch(token ->
                    token.role() == HighlightRole.KEY));
            assertTrue(tokens.stream().anyMatch(token ->
                    token.role() == HighlightRole.COMMENT));
        }
    }

    @Test
    void textMateTokensDoNotSplitSupplementaryCharacters() {
        String line = "\t<div class=\"icon\">📈</div>";
        DocumentContent html =
                content(Path.of("index.html"), DocumentKind.HTML, line);

        try (HighlightSession session =
                     HighlightEngines.open(
                             HighlightEngines.TEXTMATE, html)) {
            for (HighlightToken token : session.tokensForLine(0)) {
                assertFalse(splitsSurrogatePair(line, token.start()));
                assertFalse(splitsSurrogatePair(line, token.end()));
            }
        }
    }

    private static boolean splitsSurrogatePair(String text, int offset) {
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }

    @Test
    void registeredEngineCanBeSelectedWithoutRendererChanges() {
        HighlightEngines.register(new HighlightEngine() {
            @Override
            public String id() {
                return "test-comments";
            }

            @Override
            public String displayName() {
                return "Test Comments";
            }

            @Override
            public int automaticPriority() {
                return -100;
            }

            @Override
            public HighlightSession open(
                    DocumentContent document,
                    HighlightLanguage language) {
                return line -> {
                    String text =
                            HighlightEngines.lineText(document, line);
                    return text.isEmpty() ? List.of()
                            : List.of(new HighlightToken(
                                    0, text.length(),
                                    HighlightRole.COMMENT));
                };
            }
        });
        DocumentContent document =
                content(DocumentKind.PLAIN, "plugged in");

        try (HighlightSession session =
                     HighlightEngines.open("test-comments", document)) {
            assertEquals(HighlightRole.COMMENT,
                    session.tokensForLine(0).getFirst().role());
        }
        assertTrue(HighlightEngines.choices().stream().anyMatch(
                choice -> choice.id().equals("test-comments")));
    }

    @Test
    void brokenOrInvalidEnginesDegradeSafely() {
        HighlightEngines.register(new HighlightEngine() {
            @Override
            public String id() {
                return "test-broken";
            }

            @Override
            public String displayName() {
                return "Test Broken";
            }

            @Override
            public HighlightSession open(
                    DocumentContent document,
                    HighlightLanguage language) {
                throw new IllegalStateException("broken provider");
            }
        });
        HighlightEngines.register(new HighlightEngine() {
            @Override
            public String id() {
                return "test-invalid";
            }

            @Override
            public String displayName() {
                return "Test Invalid";
            }

            @Override
            public HighlightSession open(
                    DocumentContent document,
                    HighlightLanguage language) {
                return line -> List.of(new HighlightToken(
                        1, 2, HighlightRole.KEYWORD));
            }
        });
        DocumentContent java =
                content(DocumentKind.JAVA, "public class Example {}");

        try (HighlightSession broken =
                        HighlightEngines.open("test-broken", java);
                HighlightSession invalid =
                        HighlightEngines.open("test-invalid", java)) {
            assertTrue(broken.tokensForLine(0).stream().anyMatch(
                    token -> token.role() == HighlightRole.KEYWORD));
            assertEquals(List.of(new HighlightToken(
                            0, java.text().length(),
                            HighlightRole.PLAIN)),
                    invalid.tokensForLine(0));
        }
    }

    private static DocumentContent content(
            DocumentKind kind, String text) {
        return content(Path.of("test.txt"), kind, text);
    }

    private static DocumentContent content(
            Path path, DocumentKind kind, String text) {
        return new DocumentContent(
                path, kind, text,
                StandardCharsets.UTF_8, text.length(), 0,
                new int[]{0});
    }
}
