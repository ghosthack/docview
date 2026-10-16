package io.github.ghosthack.docview;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressions for the pre-release security and correctness review. */
final class SafetyRegressionTest {

    private static final Duration FAST = Duration.ofSeconds(5);

    private static String page(DocumentKind kind, String text) {
        return SafeMarkupRenderer.page(DocumentContent.of(Path.of("t"), kind, text),
                ReaderStyle.defaults(), HighlightEngines.NONE);
    }

    @Test
    void inlineCodeSpansStayLinearWithControlCharacters() {
        String line = "\u0001".repeat(4000) + "`a`".repeat(4000);
        assertTimeoutPreemptively(FAST, () -> Blocks.fromMarkdown(line, HighlightEngines.NONE));
        String many = "`a` ".repeat(60_000);
        assertTimeoutPreemptively(FAST, () -> page(DocumentKind.MARKDOWN, many));
    }

    @Test
    void privateUseMarkersInTheInputAreNeutralised() {
        String html = SafeMarkupRenderer.inline("a 0 `x` b");
        assertTrue(html.contains("<code>x</code>"));
        assertFalse(html.contains(""));
    }

    @Test
    void linkRegexAndFenceInfoStayLinear() {
        assertTimeoutPreemptively(FAST, () ->
                page(DocumentKind.MARKDOWN, "[".repeat(200_000)));
        assertTimeoutPreemptively(FAST, () ->
                page(DocumentKind.MARKDOWN, "[a](".repeat(50_000)));
        assertTimeoutPreemptively(FAST, () -> Blocks.fromMarkdown(
                "```" + ".".repeat(400_000) + "\nx\n```", HighlightEngines.NONE));
    }

    @Test
    void deepQuotesAndListsDoNotOverflowTheStack() {
        String quotes = ">".repeat(20_000) + " x";
        assertTimeoutPreemptively(FAST, () -> {
            Blocks.fromMarkdown(quotes, HighlightEngines.NONE);
            page(DocumentKind.MARKDOWN, quotes);
        });
        var lists = new StringBuilder();
        for (int i = 0; i < 5000; i++) lists.append(" ".repeat(i)).append("- [ ] x\n");
        assertTimeoutPreemptively(FAST, () -> {
            Blocks.fromMarkdown(lists.toString(), HighlightEngines.NONE);
            page(DocumentKind.MARKDOWN, lists.toString());
        });
    }

    @Test
    void xmlLeafPillsAreBudgeted() {
        String xml = "<r>" + "<a/>".repeat(200_000) + "</r>";
        XmlTree.Tree tree = assertTimeoutPreemptively(FAST, () -> XmlTree.parse(xml));
        List<XmlTree.Entry> compact = tree.root().compact();
        assertEquals(SafeMarkupRenderer.MAX_XML_COMPACT_ENTRIES + 1, compact.size());
        assertEquals("…", compact.getLast().key());
        String html = assertTimeoutPreemptively(FAST, () -> page(DocumentKind.XML, xml));
        assertTrue(html.length() < 40_000_000, "HTML stays bounded: " + html.length());
    }

    @Test
    void oversizedXmlIsRefusedBeforeBuildingADom() {
        String xml = "<r>" + "x".repeat(SafeMarkupRenderer.MAX_XML_TREE_CHARS) + "</r>";
        XmlTree.Tree tree = XmlTree.parse(xml);
        assertNull(tree.root());
        assertTrue(tree.error().contains("too large"));
    }

    @Test
    void cssNumbersIgnoreTheDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            String html = page(DocumentKind.MARKDOWN, "text");
            assertTrue(html.contains("font-size:15.00px"), "dot decimals");
            assertFalse(html.contains("15,00px"));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void emphasisAndGlyphsNeverEnterLinkTargets() {
        Blocks.Paragraph star = (Blocks.Paragraph) Blocks.fromMarkdown(
                "see [docs](https://x.org/a*b*c) now", HighlightEngines.NONE).rows().getFirst();
        assertTrue(star.runs().stream().anyMatch(run ->
                "https://x.org/a*b*c".equals(run.link()) && run.text().equals("docs")));
        String html = page(DocumentKind.MARKDOWN, "[t](`q`😀)");
        assertTrue(html.contains("data-link-location=\"q"), html);
        assertFalse(html.matches("(?s).*data-link-location=\"[^\"]*<.*"));
    }

    @Test
    void nestedHtmlTablesKeepTheOuterRows() {
        List<Blocks.Row> rows = Blocks.fromHtml("<table><tr><td>A1</td><td>A2</td></tr>"
                + "<tr><td><table><tr><td>inner</td></tr></table></td><td>B2</td></tr>"
                + "</table>").rows();
        assertEquals(2, rows.size());
        Blocks.TableRow first = (Blocks.TableRow) rows.get(0);
        assertEquals("A1", first.cells().get(0).getFirst().text());
        Blocks.TableRow second = (Blocks.TableRow) rows.get(1);
        assertEquals("inner", second.cells().get(0).getFirst().text().strip());
        assertEquals("B2", second.cells().get(1).getFirst().text());
    }

    @Test
    void inMemoryEditsOfTheSameLengthAreDistinctCacheEntries() throws Exception {
        try (var cache = new BlockCache()) {
            var before = DocumentContent.of(Path.of("n.md"), DocumentKind.MARKDOWN, "# teh");
            var after = DocumentContent.of(Path.of("n.md"), DocumentKind.MARKDOWN, "# the");
            Blocks.Model first = cache.request(BlockCache.Key.of(before, HighlightEngines.NONE),
                    before).get(5, TimeUnit.SECONDS);
            Blocks.Model second = cache.request(BlockCache.Key.of(after, HighlightEngines.NONE),
                    after).get(5, TimeUnit.SECONDS);
            assertNotEquals(first, second);
            Blocks.Heading heading = (Blocks.Heading) second.rows().getFirst();
            assertEquals("the", heading.runs().getFirst().text());
        }
    }

    @Test
    void pagesCarryAStrictPolicyAndSafeFontNames() {
        String html = SafeMarkupRenderer.page(
                DocumentContent.of(Path.of("t"), DocumentKind.MARKDOWN, "x"),
                new ReaderStyle("Evil</style><script>", "Mono'\n", 15, false, false,
                        1.35, 0, true, 4),
                HighlightEngines.NONE);
        assertTrue(html.contains("base-uri 'none'"));
        assertTrue(html.contains("form-action 'none'"));
        assertFalse(html.contains("</style><script>"));
        assertEquals(1, html.split("</style>", -1).length - 1, "one style element");
    }

    @Test
    void nestedTableEndTagsCannotCorruptTheOuterDocument() {
        // <pre> inside a nested table used to throw from a stray </pre>.
        List<Blocks.Row> rows = Blocks.fromHtml("<table><tr><td><table><tr><td><pre>x</pre>"
                + "</td></tr></table></td></tr></table>").rows();
        assertEquals(1, rows.size());
        // Nested </b>, </ul> and </blockquote> leave the outer state alone.
        List<Blocks.Row> outer = Blocks.fromHtml("<blockquote><b><ul><li>a<table><tr><td>"
                + "<table><tr><td><b>i</b><ul><li>n</li></ul><blockquote>q</blockquote></td></tr>"
                + "</table></td></tr></table> tail</li></ul></b></blockquote>").rows();
        assertTrue(outer.stream().allMatch(row -> row.quoteDepth() == 1), outer.toString());
        // An unclosed nested table still yields rows instead of swallowing the rest.
        List<Blocks.Row> unclosed = Blocks.fromHtml(
                "<table><tr><td><table><tr><td>in</td></tr></td></tr></table><p>after</p>").rows();
        assertFalse(unclosed.isEmpty());
    }

    @Test
    void preformattedTextInACellStaysInThatCell() {
        List<Blocks.Row> rows = Blocks.fromHtml(
                "<table><tr><td><pre>a\n  b</pre></td><td>c</td></tr></table>").rows();
        assertEquals(1, rows.size());
        Blocks.TableRow row = (Blocks.TableRow) rows.getFirst();
        Blocks.Run cell = row.cells().get(0).getFirst();
        assertTrue(cell.code());
        assertEquals("a b", cell.text());
    }
}
