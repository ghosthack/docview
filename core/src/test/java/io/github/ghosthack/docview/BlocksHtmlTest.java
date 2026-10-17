package io.github.ghosthack.docview;

import io.github.ghosthack.docview.Blocks.CodeLine;
import io.github.ghosthack.docview.Blocks.Heading;
import io.github.ghosthack.docview.Blocks.ListItem;
import io.github.ghosthack.docview.Blocks.Paragraph;
import io.github.ghosthack.docview.Blocks.Row;
import io.github.ghosthack.docview.Blocks.Rule;
import io.github.ghosthack.docview.Blocks.Run;
import io.github.ghosthack.docview.Blocks.TableRow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlocksHtmlTest {

    @Test
    void dropsScriptsStylesAndTitlesLikeTheWebReader() {
        List<Row> rows = Blocks.fromHtml("<html><head><title>T</title>"
                + "<style>p{color:red}</style></head><body><p>Kept</p>"
                + "<script>alert(1)</script><object>x</object></body></html>").rows();

        assertEquals(1, rows.size());
        assertEquals("Kept", text(((Paragraph) rows.getFirst()).runs()));
    }

    @Test
    void mapsBlocksAndInlineStyles() {
        List<Row> rows = Blocks.fromHtml("<h2>Head</h2><p>A <b>bold</b> <i>it</i> <em>em</em> "
                + "<code>x()</code> <a href=\"b.html\">go</a></p><hr>"
                + "<blockquote><p>quoted</p></blockquote>").rows();

        Heading heading = assertInstanceOf(Heading.class, rows.get(0));
        assertEquals(2, heading.level());
        List<Run> runs = ((Paragraph) rows.get(1)).runs();
        assertTrue(runs.stream().anyMatch(r -> r.strong() && r.text().equals("bold")));
        // The Reader styles em bold italic; HTML i stays plain italic.
        assertTrue(runs.stream().anyMatch(r -> r.italic() && !r.emphasis()
                && r.text().equals("it")));
        assertTrue(runs.stream().anyMatch(r -> r.emphasis() && r.text().equals("em")));
        assertTrue(runs.stream().anyMatch(r -> r.code() && r.text().equals("x()")));
        assertTrue(runs.stream().anyMatch(r -> "b.html".equals(r.link())));
        assertInstanceOf(Rule.class, rows.get(2));
        assertEquals(1, rows.get(3).quoteDepth());
    }

    @Test
    void collapsesWhitespaceOutsidePreButKeepsItInside() {
        List<Row> rows = Blocks.fromHtml("<p>  many\n   spaces  </p>"
                + "<pre>line 1\n    indented</pre>").rows();

        assertEquals("many spaces", text(((Paragraph) rows.get(0)).runs()));
        assertEquals("line 1", ((CodeLine) rows.get(1)).text());
        assertEquals("    indented", ((CodeLine) rows.get(2)).text());
    }

    @Test
    void nestedListsCarryDepthAndOrdinals() {
        List<Row> rows = Blocks.fromHtml(
                "<ol><li>one</li><li>two<ul><li>inner</li></ul></li></ol>").rows();

        assertEquals("1.", ((ListItem) rows.get(0)).prefix());
        assertEquals("2.", ((ListItem) rows.get(1)).prefix());
        ListItem inner = (ListItem) rows.get(2);
        assertEquals("•", inner.prefix());
        assertEquals(1, inner.depth());
    }

    @Test
    void listItemsWrappingBlocksKeepTheirBulletsAndNumbers() {
        List<Row> rows = Blocks.fromHtml("<ul><li><p>one</p></li><li><p>two</p></li></ul>"
                + "<ol><li><div>first</div></li><li><h3>second</h3></li></ol>"
                + "<ul><li></li></ul><p>after</p>").rows();

        assertEquals(5, rows.size());
        assertEquals("•", ((ListItem) rows.get(0)).prefix());
        assertEquals("one", text(((ListItem) rows.get(0)).runs()));
        assertEquals("•", ((ListItem) rows.get(1)).prefix());
        assertEquals("1.", ((ListItem) rows.get(2)).prefix());
        assertEquals("first", text(((ListItem) rows.get(2)).runs()));
        assertEquals("2.", ((ListItem) rows.get(3)).prefix());
        assertEquals("second", text(((ListItem) rows.get(3)).runs()));
        // An empty item does not turn the following paragraph into a bullet.
        assertEquals("after", text(assertInstanceOf(Paragraph.class, rows.get(4)).runs()));
    }

    @Test
    void headingsAndListItemsInCellsDoNotRestyleTextAfterTheTable() {
        List<Row> rows = Blocks.fromHtml("<table><tr><td><h3>cell</h3></td></tr></table>"
                + "after text"
                + "<table><tr><td><ul><li>x</li></ul></td></tr></table><b>after</b>").rows();

        assertInstanceOf(TableRow.class, rows.get(0));
        assertEquals("after text", text(assertInstanceOf(Paragraph.class, rows.get(1)).runs()));
        assertInstanceOf(TableRow.class, rows.get(2));
        assertEquals("after", text(assertInstanceOf(Paragraph.class, rows.get(3)).runs()));
    }

    @Test
    void blockChildrenOfACellAreSeparated() {
        List<Row> rows = Blocks.fromHtml("<table><tr><td><p>Hello</p><p>World</p></td>"
                + "<td><div>one</div>\n<div>two</div></td><td><b>A</b><i>B</i></td>"
                + "</tr></table>").rows();

        TableRow row = (TableRow) rows.getFirst();
        assertEquals("Hello World", text(row.cells().get(0)));
        assertEquals("one two", text(row.cells().get(1)));
        assertEquals("AB", text(row.cells().get(2)));
    }

    @Test
    void tablesDetectHeadersAndPadShortRows() {
        List<Row> rows = Blocks.fromHtml("<table><caption>Cap</caption>"
                + "<tr><th>A</th><th>B</th></tr><tr><td>1</td></tr></table>").rows();

        assertEquals("Cap", text(((Paragraph) rows.get(0)).runs()));
        TableRow header = (TableRow) rows.get(1);
        TableRow body = (TableRow) rows.get(2);
        assertTrue(header.header());
        assertFalse(body.header());
        assertTrue(body.last());
        assertEquals(2, body.cells().size());
        assertEquals(2, header.table().columns());
    }


    @Test
    void nestingPastTheDepthCapFallsBackToSourceLinesQuickly() {
        String html = "<b>".repeat(50_000) + "</i>".repeat(50_000);
        Blocks.Model model = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> Blocks.fromHtml(html));
        assertEquals(1, model.rows().size());
        assertEquals(html, assertInstanceOf(CodeLine.class, model.rows().getFirst()).text());

        Blocks.Model nested = Blocks.fromHtml("<div>".repeat(100) + "deep" + "</div>".repeat(100));
        assertEquals("deep", text(assertInstanceOf(Paragraph.class, nested.rows().getFirst()).runs()));
    }
    private static String text(List<Run> runs) {
        var out = new StringBuilder();
        for (Run run : runs) out.append(run.text());
        return out.toString();
    }
}
