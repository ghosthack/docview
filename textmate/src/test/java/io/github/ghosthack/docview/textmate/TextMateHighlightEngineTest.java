package io.github.ghosthack.docview.textmate;

import io.github.ghosthack.docview.DocumentContent;
import io.github.ghosthack.docview.DocumentKind;
import io.github.ghosthack.docview.HighlightEngines;
import io.github.ghosthack.docview.HighlightLanguage;
import io.github.ghosthack.docview.HighlightRole;
import io.github.ghosthack.docview.HighlightSession;
import io.github.ghosthack.docview.HighlightToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

class TextMateHighlightEngineTest {

    @Test
    void oneHostileLineIsCutOffByTheHardDeadline() {
        // From the security re-verification: one 1,999-backtick Markdown line
        // took 84-99 s; twenty such lines 103 s despite the budgets.
        String text = ("`".repeat(1_999) + "\n").repeat(20);
        DocumentContent document = DocumentContent.of(Path.of("a.md"), DocumentKind.MARKDOWN, text);
        long started = System.nanoTime();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10), () -> {
                    try (HighlightSession session =
                                 HighlightEngines.open(HighlightEngines.TEXTMATE, document)) {
                        for (int line = 0; line < 20; line++) session.tokensForLine(line);
                    }
                });
        long millis = (System.nanoTime() - started) / 1_000_000;
        org.junit.jupiter.api.Assertions.assertTrue(millis < 5_000, "took " + millis + " ms");
    }

    @Test
    void aSkippedLongLineDoesNotLeaveAStaleComment() {
        String text = "/*\n" + "x".repeat(2_500) + " */\nint x = 1;\n";
        DocumentContent document = DocumentContent.of(Path.of("A.java"), DocumentKind.JAVA, text);
        try (HighlightSession session = HighlightEngines.open(HighlightEngines.TEXTMATE, document)) {
            var tokens = session.tokensForLine(2);
            org.junit.jupiter.api.Assertions.assertFalse(tokens.stream().allMatch(
                    token -> token.role() == HighlightRole.COMMENT), tokens.toString());
        }
    }

    @Test
    void hostileLinesAndDocumentsStayBounded() {
        // From the pre-release security review: 34 s and 27 s before the
        // line-length cap and the session/document budgets.
        String fence = "```markdown\n" + "[".repeat(20_000) + "](\n```\n";
        var js = new StringBuilder("```js\n");
        for (int i = 0; i < 200; i++) js.append("x=").append("(".repeat(20_000)).append('\n');
        js.append("```\n");
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5), () -> {
                    for (String markdown : new String[]{fence, js.toString()}) {
                        var model = io.github.ghosthack.docview.Blocks.fromMarkdown(
                                markdown, HighlightEngines.AUTOMATIC);
                        for (var row : model.rows()) {
                            if (row instanceof io.github.ghosthack.docview.Blocks.CodeLine line) {
                                line.block().tokens(line.line(), line.text());
                            }
                        }
                        io.github.ghosthack.docview.SafeMarkupRenderer.page(
                                DocumentContent.of(Path.of("x.md"), DocumentKind.MARKDOWN, markdown),
                                io.github.ghosthack.docview.ReaderStyle.defaults(),
                                HighlightEngines.AUTOMATIC);
                    }
                });
    }

    @Test
    void coldGrammarCompilationDoesNotCutTheFirstLinesShort() {
        // From review: in a fresh JVM, compiling the embedded JS on first use
        // ran past TM4E's 50 ms wall-clock limit, so the line stopped early
        // (partial tokens, mid-line state carried into the next line).
        String script = "<script>var a = 1; function f(x) { return x * 2; }</script>";
        DocumentContent html = content(Path.of("cold.html"), DocumentKind.HTML,
                script + "\n" + script + "\n<p class=\"c\">ok</p>");
        try (HighlightSession session = new TextMateHighlightEngine().open(
                html, HighlightLanguage.forDocument(html))) {
            for (int line = 0; line < 2; line++) {
                var tokens = session.tokensForLine(line);
                assertPartition(html, line, tokens);
                assertTrue(tokens.stream().anyMatch(token -> token.role() == HighlightRole.NUMBER),
                        line + ": " + tokens);
                assertTrue(tokens.stream().anyMatch(token -> token.role() == HighlightRole.KEYWORD
                        || token.role() == HighlightRole.TYPE), line + ": " + tokens);
            }
            var last = session.tokensForLine(2);
            assertTrue(last.stream().anyMatch(token -> token.role() == HighlightRole.TAG), last.toString());
            assertTrue(last.stream().noneMatch(token -> token.role() == HighlightRole.STRING
                    && token.end() - token.start() > 3), last.toString());
        }
    }

    @Test
    void aLineWhoseGrammarRuleFailsToCompileDoesNotStallTheSession() {
        // The bundled JS/TS grammars' `using` declaration has a lookbehind
        // Joni cannot compile; TM4E throws for that line on every call.
        DocumentContent js = content(Path.of("using.js"), DocumentKind.SCRIPT,
                "{\n  using res = getResource();\n  const b = \"y\"; // after\n}\nlet c = 42; // later");
        try (HighlightSession session = new TextMateHighlightEngine().open(
                js, HighlightLanguage.forDocument(js))) {
            for (int line = 0; line < js.lineCount(); line++) {
                assertPartition(js, line, session.tokensForLine(line));
            }
            var later = session.tokensForLine(4);
            assertTrue(later.stream().anyMatch(token -> token.role() == HighlightRole.NUMBER),
                    later.toString());
            assertTrue(later.stream().anyMatch(token -> token.role() == HighlightRole.COMMENT),
                    later.toString());
        }
    }

    @Test
    void registersThroughServiceLoaderWhenOnTheClasspath() {
        assertTrue(HighlightEngines.choices().stream().anyMatch(
                choice -> choice.id().equals(HighlightEngines.TEXTMATE)));
    }

    @Test
    void everyBundledGrammarTokenizesAndPartitionsLines() {
        var samples = new LinkedHashMap<Path, Sample>();
        samples.put(Path.of("sample.rb"), new Sample(DocumentKind.RUBY,
                "unknown = ARGV.reject { |a| a =~ /\\A--(all|wide)\\z/ }\n"
                        + "abort \"unknown: #{unknown.join(' ')}\" if unknown.any?"));
        samples.put(Path.of("sample.html"), new Sample(DocumentKind.HTML,
                "<meta name=\"viewport\" content=\"width=device-width\">"));
        samples.put(Path.of("sample.yaml"), new Sample(DocumentKind.YAML,
                "network:\n  cidr: 198.51.100.0/24\n  ram_gb: 32  # memory"));
        samples.put(Path.of("sample.md"), new Sample(DocumentKind.MARKDOWN,
                "# Heading\n\n```\nP1 P2 | 3.3V\n```"));
        samples.put(Path.of("sample.xml"), new Sample(DocumentKind.XML,
                "<project>\n  <!-- release -->\n  <java.release>26</java.release>\n</project>"));
        samples.put(Path.of("sample.json"), new Sample(DocumentKind.JSON,
                "{\"enabled\": true, \"count\": 3}"));
        samples.put(Path.of("Sample.java"), new Sample(DocumentKind.JAVA,
                "public final class Sample { /* note */ int value = 3; }"));
        samples.put(Path.of("sample.sh"), new Sample(DocumentKind.SHELL,
                "export PATH=\"$HOME/.local/bin:$PATH\""));
        samples.put(Path.of("sample.js"), new Sample(DocumentKind.SCRIPT,
                "const answer = value => value ?? 42;"));
        samples.put(Path.of("sample.ts"), new Sample(DocumentKind.SCRIPT,
                "interface User { name: string }\nconst user: User = { name: 'Ada' };"));
        samples.put(Path.of("sample.css"), new Sample(DocumentKind.SCRIPT,
                ".reader { color: #fff; font-size: 1rem; }"));
        samples.put(Path.of("sample.py"), new Sample(DocumentKind.SCRIPT,
                "def greet(name: str) -> str:\n    return f\"Hello {name}\""));
        samples.put(Path.of("sample.sql"), new Sample(DocumentKind.SQL,
                "SELECT name FROM users WHERE active = TRUE;"));

        TextMateHighlightEngine engine = new TextMateHighlightEngine();
        samples.forEach((path, sample) -> {
            DocumentContent document =
                    content(path, sample.kind(), sample.text());
            HighlightLanguage language =
                    HighlightLanguage.forDocument(document);
            assertTrue(engine.supports(language), language.id());
            try (HighlightSession session =
                         engine.open(document, language)) {
                boolean styled = false;
                for (int line = document.lineCount() - 1;
                        line >= 0; line--) {
                    List<HighlightToken> tokens =
                            session.tokensForLine(line);
                    assertPartition(document, line, tokens);
                    styled |= tokens.stream().anyMatch(token ->
                            token.role() != HighlightRole.PLAIN);
                }
                assertTrue(styled, language.id());
            }
        });
    }

    @Test
    void stateFlowsAcrossLinesWhenRowsArriveOutOfOrder() {
        DocumentContent java = content(
                Path.of("Sample.java"), DocumentKind.JAVA,
                "class Sample {\n  /* first\n     second */\n}");

        try (HighlightSession session =
                     new TextMateHighlightEngine().open(
                             java, HighlightLanguage.forDocument(java))) {
            assertTrue(session.tokensForLine(2).stream().allMatch(
                    token -> token.role() == HighlightRole.COMMENT));
            assertPartition(java, 0, session.tokensForLine(0));
            assertEquals(session.tokensForLine(2),
                    session.tokensForLine(2));
        }
    }

    @Test
    void shellFunctionDefinitionsDoNotEmitTokenizerWarningsAtInfo() {
        DocumentContent shell = content(
                Path.of("functions.sh"), DocumentKind.SHELL,
                "alpha() {\n"
                        + "  echo one\n"
                        + "}\n"
                        + "function beta() (\n"
                        + "  echo two\n"
                        + ")");
        AtomicInteger warnings = new AtomicInteger();
        Handler warningCounter = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue()
                        >= Level.INFO.intValue()
                        && record.getMessage().contains(
                                "Grammar is in an endless loop")) {
                    warnings.incrementAndGet();
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger tokenizerLogger = Logger.getLogger(
                "io.github.ghosthack.docview.textmate.internal.tm4e.core.internal.grammar.LineTokenizer");
        Level originalLevel = tokenizerLogger.getLevel();
        tokenizerLogger.setLevel(Level.INFO);
        tokenizerLogger.addHandler(warningCounter);
        try (HighlightSession session =
                     new TextMateHighlightEngine().open(
                             shell,
                             HighlightLanguage.forDocument(shell))) {
            for (int line = 0; line < shell.lineCount(); line++) {
                assertPartition(shell, line,
                        session.tokensForLine(line));
            }
        } finally {
            tokenizerLogger.removeHandler(warningCounter);
            tokenizerLogger.setLevel(originalLevel);
        }
        assertEquals(0, warnings.get());
    }

    @Test
    void representativeScopesMapToApplicationRoles() {
        DocumentContent xml = content(
                Path.of("pom.xml"), DocumentKind.XML,
                "<project xmlns=\"urn:test\">\n"
                        + "  <!-- release 26 -->\n"
                        + "  <java.release>26</java.release>\n"
                        + "</project>");
        DocumentContent yaml = content(
                Path.of("devices.yaml"), DocumentKind.YAML,
                "network:\n"
                        + "  cidr: 198.51.100.0/24\n"
                        + "  ram_gb: 32  # memory");

        try (HighlightSession xmlSession =
                     new TextMateHighlightEngine().open(
                             xml, HighlightLanguage.forDocument(xml));
                HighlightSession yamlSession =
                     new TextMateHighlightEngine().open(
                             yaml, HighlightLanguage.forDocument(yaml))) {
            assertTrue(xmlSession.tokensForLine(0).stream().anyMatch(
                    token -> token.role() == HighlightRole.TAG));
            assertTrue(xmlSession.tokensForLine(0).stream().anyMatch(
                    token -> token.role() == HighlightRole.KEY));
            assertTrue(xmlSession.tokensForLine(1).stream().allMatch(
                    token -> token.role() == HighlightRole.PLAIN
                            || token.role() == HighlightRole.COMMENT));
            assertTrue(yamlSession.tokensForLine(0).stream().anyMatch(
                    token -> token.role() == HighlightRole.KEY));
            assertTrue(yamlSession.tokensForLine(2).stream().anyMatch(
                    token -> token.role() == HighlightRole.NUMBER));
            assertTrue(yamlSession.tokensForLine(2).stream().anyMatch(
                    token -> token.role() == HighlightRole.COMMENT));
        }
    }

    @Test
    void malformedAndVeryLongLinesStillReturnAValidPartition() {
        DocumentContent ruby = content(
                Path.of("broken.rb"), DocumentKind.RUBY,
                "value = \"unterminated\n"
                        + ("x".repeat(100_000))
                        + "\n=end without begin");

        try (HighlightSession session =
                     new TextMateHighlightEngine().open(
                             ruby, HighlightLanguage.forDocument(ruby))) {
            for (int line = 0; line < ruby.lineCount(); line++) {
                assertPartition(ruby, line,
                        session.tokensForLine(line));
            }
        }
    }

    private static void assertPartition(
            DocumentContent document, int line,
            List<HighlightToken> tokens) {
        int length = document.lineEnd(line) - document.lineStart(line);
        if (length == 0) {
            assertTrue(tokens.isEmpty());
            return;
        }
        int cursor = 0;
        for (HighlightToken token : tokens) {
            assertEquals(cursor, token.start());
            assertTrue(token.end() > token.start());
            cursor = token.end();
        }
        assertEquals(length, cursor);
    }

    private static DocumentContent content(
            Path path, DocumentKind kind, String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        int[] starts = new int[lines];
        int line = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') starts[line++] = i + 1;
        }
        return new DocumentContent(
                path, kind, text, StandardCharsets.UTF_8,
                text.length(), 0, starts);
    }

    private record Sample(DocumentKind kind, String text) {
    }
}
