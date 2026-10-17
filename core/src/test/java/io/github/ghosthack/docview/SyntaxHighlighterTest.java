package io.github.ghosthack.docview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class SyntaxHighlighterTest {

    @Test
    void javaHighlightsWithoutChangingText() {
        String line = "public record Thing(int count) { // note";
        var segments = SyntaxHighlighter.highlightLine(line, DocumentKind.JAVA, true);
        assertEquals(line, segments.stream().map(SyntaxHighlighter.Segment::text)
                .collect(java.util.stream.Collectors.joining()));
        assertTrue(segments.stream().anyMatch(s ->
                s.style() == SyntaxHighlighter.Style.KEYWORD && s.text().contains("public")));
        assertTrue(segments.stream().anyMatch(s ->
                s.style() == SyntaxHighlighter.Style.NUMBER || s.style()
                        == SyntaxHighlighter.Style.COMMENT));
    }

    @Test
    void disablingHighlightingReturnsOnePlainSegment() {
        var segments = SyntaxHighlighter.highlightLine("<tag>1</tag>",
                DocumentKind.XML, false);
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Style.PLAIN, segments.getFirst().style());
    }

    @Test
    void markdownProseDoesNotUseProgrammingKeywordColors() {
        String heading = "## Use";
        var headingSegments = SyntaxHighlighter.highlightLine(
                heading, DocumentKind.MARKDOWN, true);
        assertEquals(1, headingSegments.size());
        assertEquals(SyntaxHighlighter.Style.HEADING,
                headingSegments.getFirst().style());

        String prose = "For WIC, declare the top-level artifact; Maven brings in `core` and";
        var proseSegments = SyntaxHighlighter.highlightLine(
                prose, DocumentKind.MARKDOWN, true);
        assertEquals(prose, proseSegments.stream()
                .map(SyntaxHighlighter.Segment::text)
                .collect(java.util.stream.Collectors.joining()));
        assertTrue(proseSegments.stream().noneMatch(
                segment -> segment.style() == SyntaxHighlighter.Style.KEYWORD));
        assertTrue(proseSegments.stream().anyMatch(
                segment -> segment.style() == SyntaxHighlighter.Style.EMPHASIS
                        && segment.text().equals("`core`")));
    }

    @Test
    void plainTextDoesNotUseProgrammingKeywordColors() {
        var segments = SyntaxHighlighter.highlightLine(
                "For prose", DocumentKind.PLAIN, true);

        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Style.PLAIN, segments.getFirst().style());
        assertEquals("For prose", segments.getFirst().text());
    }

    @Test
    void xmlCommentsUseCommentColorRatherThanTagColor() {
        String line = "        <!-- Java release shared by every panama-media module. -->";
        var segments = SyntaxHighlighter.highlightLine(
                line, DocumentKind.XML, true);

        assertEquals(line, segments.stream()
                .map(SyntaxHighlighter.Segment::text)
                .collect(java.util.stream.Collectors.joining()));
        assertEquals(SyntaxHighlighter.Style.PLAIN, segments.getFirst().style());
        assertEquals(SyntaxHighlighter.Style.COMMENT, segments.get(1).style());
        assertEquals("<!-- Java release shared by every panama-media module. -->",
                segments.get(1).text());
    }

    @Test
    void xmlTextNodeNumbersRemainPlainText() {
        String line = "<java.release>26</java.release>";
        var segments = SyntaxHighlighter.highlightLine(
                line, DocumentKind.XML, true);

        assertEquals(3, segments.size());
        assertEquals(SyntaxHighlighter.Style.TAG, segments.get(0).style());
        assertEquals(SyntaxHighlighter.Style.PLAIN, segments.get(1).style());
        assertEquals("26", segments.get(1).text());
        assertEquals(SyntaxHighlighter.Style.TAG, segments.get(2).style());
        assertTrue(segments.stream().noneMatch(
                segment -> segment.style() == SyntaxHighlighter.Style.NUMBER));
    }

    @Test
    void multilineXmlOpeningTagColorsItsFirstLineElementName() {
        String line = "  <project xmlns=\"http://maven.apache.org/POM/4.0.0\"";
        var segments = SyntaxHighlighter.highlightLine(
                line, DocumentKind.XML, true);

        assertEquals(line, segments.stream()
                .map(SyntaxHighlighter.Segment::text)
                .collect(java.util.stream.Collectors.joining()));
        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.TAG
                        && segment.text().equals("<project")));
        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.STRING
                        && segment.text().equals(
                                "\"http://maven.apache.org/POM/4.0.0\"")));
    }

    @Test
    void htmlSeparatesElementAttributesValuesAndText() {
        String meta = "    <meta name=\"viewport\" content=\"width=device-width\">";
        var metaSegments = SyntaxHighlighter.highlightLine(
                meta, DocumentKind.HTML, true);

        assertEquals(meta, metaSegments.stream()
                .map(SyntaxHighlighter.Segment::text)
                .collect(java.util.stream.Collectors.joining()));
        assertTrue(metaSegments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.TAG
                        && segment.text().equals("<meta")));
        assertTrue(metaSegments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.KEY
                        && segment.text().equals("name")));
        assertTrue(metaSegments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.KEY
                        && segment.text().equals("content")));
        assertTrue(metaSegments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.STRING
                        && segment.text().equals("\"viewport\"")));
        assertTrue(metaSegments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.STRING
                        && segment.text().equals("\"width=device-width\"")));

        String title = "    <title>demo · Sample Readings Archive</title>";
        var titleSegments = SyntaxHighlighter.highlightLine(
                title, DocumentKind.HTML, true);
        assertTrue(titleSegments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.PLAIN
                        && segment.text().contains(
                                "demo · Sample Readings Archive")));
        assertTrue(titleSegments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.TAG
                        && segment.text().equals("</title>")));

        String scriptText = "if (left > right) return left;";
        var scriptSegments = SyntaxHighlighter.highlightLine(
                scriptText, DocumentKind.HTML, true);
        assertTrue(scriptSegments.stream().noneMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.TAG));
    }

    @Test
    void yamlKeepsUnquotedValuesConsistentlyPlain() {
        for (String line : java.util.List.of(
                "  cidr: 198.51.100.0/24",
                "  gateway: 198.51.100.1",
                "  mac: 02:00:5E:10:00:01",
                "  notes: checked 2026-07-25",
                "  model: Sample Phone 7",
                "  ram_gb: 32",
                "  vram_gb: null",
                "  enabled: true")) {
            var segments = SyntaxHighlighter.highlightLine(
                    line, DocumentKind.YAML, true);

            assertEquals(line, segments.stream()
                    .map(SyntaxHighlighter.Segment::text)
                    .collect(java.util.stream.Collectors.joining()));
            assertTrue(segments.stream().noneMatch(segment ->
                    segment.style() == SyntaxHighlighter.Style.NUMBER
                            || segment.style()
                                    == SyntaxHighlighter.Style.KEYWORD),
                    line);
        }
    }

    @Test
    void yamlStillHighlightsKeysCommentsAndQuotedStrings() {
        String line = "  status: \"ready\" # current state";
        var segments = SyntaxHighlighter.highlightLine(
                line, DocumentKind.YAML, true);

        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.KEY
                        && segment.text().contains("status")));
        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.STRING
                        && segment.text().equals("\"ready\"")));
        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.COMMENT
                        && segment.text().equals("# current state")));
    }

    @Test
    void markdownSourceUsesFenceLanguageInsteadOfMarkdownEmphasis() {
        String markdown = "Before *em*\n"
                + "```\n"
                + "   \\_ 3.3V _/ \\__ GND _/ \\__ 5V __/\n"
                + "```\n"
                + "```java\n"
                + "public record Pin(int number) {}\n"
                + "```";
        DocumentContent document = content(DocumentKind.MARKDOWN, markdown);
        var kinds = SyntaxHighlighter.syntaxKinds(document);

        assertEquals(DocumentKind.MARKDOWN, kinds.get(0));
        assertEquals(DocumentKind.MARKDOWN, kinds.get(1));
        assertEquals(DocumentKind.PLAIN, kinds.get(2));
        assertEquals(DocumentKind.MARKDOWN, kinds.get(3));
        assertEquals(DocumentKind.MARKDOWN, kinds.get(4));
        assertEquals(DocumentKind.JAVA, kinds.get(5));
        assertEquals(DocumentKind.MARKDOWN, kinds.get(6));

        var diagram = SyntaxHighlighter.highlightLine(
                document.text().substring(
                        document.lineStart(2), document.lineEnd(2)),
                kinds.get(2), true);
        assertEquals(1, diagram.size());
        assertEquals(SyntaxHighlighter.Style.PLAIN,
                diagram.getFirst().style());

        var java = SyntaxHighlighter.highlightLine(
                document.text().substring(
                        document.lineStart(5), document.lineEnd(5)),
                kinds.get(5), true);
        assertTrue(java.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.KEYWORD
                        && segment.text().equals("public")));
    }

    @Test
    void rubyRegexDashesDoNotBecomeSqlComments() {
        for (String line : java.util.List.of(
                "os_arg = ARGV.grep(/\\A--os=/).first.to_s.sub('--os=', '')",
                "unknown = ARGV.reject { |a| a =~ /\\A--(tsv|all|wide|os=.*)\\z/ }")) {
            var segments = SyntaxHighlighter.highlightLine(
                    line, DocumentKind.RUBY, true);

            assertEquals(line, segments.stream()
                    .map(SyntaxHighlighter.Segment::text)
                    .collect(java.util.stream.Collectors.joining()));
            assertTrue(segments.stream().noneMatch(segment ->
                    segment.style() == SyntaxHighlighter.Style.COMMENT), line);
            assertTrue(segments.stream().anyMatch(segment ->
                    segment.style() == SyntaxHighlighter.Style.STRING
                            && segment.text().startsWith("/\\A--")), line);
        }
    }

    @Test
    void rubyUsesRubyCommentsKeywordsAndPercentLiterals() {
        String source = "values = %w[macos linux win] if ready # supported";
        var segments = SyntaxHighlighter.highlightLine(
                source, DocumentKind.RUBY, true);

        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.STRING
                        && segment.text().equals("%w[macos linux win]")));
        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.KEYWORD
                        && segment.text().equals("if")));
        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.COMMENT
                        && segment.text().equals("# supported")));
    }

    @Test
    void tclUsesCommandsVariablesBracedWordsAndCommandComments() {
        String source =
                "proc greet {name} { puts $name; incr count 2; # greeting";
        var segments = SyntaxHighlighter.highlightLine(
                source, DocumentKind.TCL, true);

        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.KEYWORD
                        && segment.text().equals("proc")));
        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.KEYWORD
                        && segment.text().equals("puts")));
        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.STRING
                        && segment.text().equals("{name}")));
        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.KEY
                        && segment.text().equals("$name")));
        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.NUMBER
                        && segment.text().equals("2")));
        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.COMMENT
                        && segment.text().strip().equals("# greeting")));
    }

    @Test
    void tclHashIsNotAlwaysAComment() {
        String source = "puts \"# text\"; set value #not-a-comment";
        var segments = SyntaxHighlighter.highlightLine(
                source, DocumentKind.TCL, true);

        assertTrue(segments.stream().noneMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.COMMENT));
        assertTrue(segments.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.STRING
                        && segment.text().equals("\"# text\"")));
    }

    @Test
    void keywordsAfterOtherIdentifiersAreHighlighted() {
        var java = SyntaxHighlighter.highlightLine(
                "foo return x = new Foo()", DocumentKind.JAVA, true);
        assertTrue(java.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.KEYWORD
                        && segment.text().equals("return")));
        assertTrue(java.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.KEYWORD
                        && segment.text().equals("new")));
        assertTrue(java.stream().noneMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.KEYWORD
                        && segment.text().contains("Foo")));

        var script = SyntaxHighlighter.highlightLine(
                "int y = foo if 1", DocumentKind.SCRIPT, true);
        assertTrue(script.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.KEYWORD
                        && segment.text().equals("if")));
        assertTrue(script.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.NUMBER
                        && segment.text().equals("1")));
    }

    @Test
    void xmlTagEndsOnlyCloseAnOpenTag() {
        String line = "a > b <c d=\"1\"/> e > f <g>";
        var segments = SyntaxHighlighter.highlightLine(
                line, DocumentKind.XML, true);

        assertEquals(java.util.List.of(
                new SyntaxHighlighter.Segment("a > b ", SyntaxHighlighter.Style.PLAIN),
                new SyntaxHighlighter.Segment("<c", SyntaxHighlighter.Style.TAG),
                new SyntaxHighlighter.Segment(" ", SyntaxHighlighter.Style.PLAIN),
                new SyntaxHighlighter.Segment("d", SyntaxHighlighter.Style.KEY),
                new SyntaxHighlighter.Segment("=", SyntaxHighlighter.Style.PLAIN),
                new SyntaxHighlighter.Segment("\"1\"", SyntaxHighlighter.Style.STRING),
                new SyntaxHighlighter.Segment("/>", SyntaxHighlighter.Style.TAG),
                new SyntaxHighlighter.Segment(" e > f ", SyntaxHighlighter.Style.PLAIN),
                new SyntaxHighlighter.Segment("<g>", SyntaxHighlighter.Style.TAG)),
                segments);
    }

    @Test
    void hostileLinesHighlightInBoundedTime() {
        int length = SyntaxHighlighter.MAX_LINE_LENGTH - 8;
        var hostile = java.util.List.of(
                // An unclosed tag used to re-run an 8 Ki lookbehind at every
                // position for every token: tens of seconds per line.
                ("<a " + "b=1 ".repeat(length / 4)).substring(0, length),
                // Every rule was re-run from the cursor for every token.
                "1,".repeat(length / 2),
                "1".repeat(length - 1) + "x",
                "[".repeat(length / 2) + "](".repeat(length / 4));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (String line : hostile) {
                for (DocumentKind kind : DocumentKind.values()) {
                    var segments = SyntaxHighlighter.highlightLine(
                            line, kind, true);
                    assertEquals(line, segments.stream()
                            .map(SyntaxHighlighter.Segment::text)
                            .collect(java.util.stream.Collectors.joining()));
                }
            }
        });

        var tag = SyntaxHighlighter.highlightLine(
                hostile.getFirst(), DocumentKind.XML, true);
        assertEquals(new SyntaxHighlighter.Segment(
                "<a", SyntaxHighlighter.Style.TAG), tag.getFirst());
        assertTrue(tag.stream().anyMatch(segment ->
                segment.style() == SyntaxHighlighter.Style.KEY));
    }

    @Test
    void linesOverTheLengthCapRenderPlain() {
        String line = "<a " + "b=1 ".repeat(SyntaxHighlighter.MAX_LINE_LENGTH / 4);
        var segments = SyntaxHighlighter.highlightLine(
                line, DocumentKind.XML, true);

        assertEquals(java.util.List.of(new SyntaxHighlighter.Segment(
                line, SyntaxHighlighter.Style.PLAIN)), segments);
    }

    private static DocumentContent content(DocumentKind kind, String text) {
        var starts = new java.util.ArrayList<Integer>();
        starts.add(0);
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n' && index + 1 < text.length()) {
                starts.add(index + 1);
            }
        }
        int[] lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        return new DocumentContent(
                Path.of("test.md"), kind, text, StandardCharsets.UTF_8,
                text.length(), 0, lineStarts);
    }
}
