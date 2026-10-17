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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlocksMarkdownTest {

    @Test
    void headingAnchorsMatchTheOutline() {
        String markdown = "# One\n\ntext\n\n## Two\n\n> # Quoted\n\n### Three\n";
        List<MarkdownOutline.Heading> outline = MarkdownOutline.headings(markdown);
        List<Heading> headings = parse(markdown).rows().stream()
                .filter(Heading.class::isInstance).map(Heading.class::cast)
                .filter(heading -> heading.anchor() != null).toList();

        assertEquals(outline.size(), headings.size());
        for (int i = 0; i < outline.size(); i++) {
            assertEquals(outline.get(i).anchor(), headings.get(i).anchor());
        }
    }

    @Test
    void inlineRunsCarryStylesLinksAndDecodedEntities() {
        Paragraph paragraph = assertInstanceOf(Paragraph.class,
                parse("A **bold** *it* `a<b` [go](x.md) & done").rows().getFirst());
        List<Run> runs = paragraph.runs();

        assertTrue(runs.stream().anyMatch(r -> r.strong() && r.text().equals("bold")));
        assertTrue(runs.stream().anyMatch(r -> r.emphasis() && r.text().equals("it")));
        assertTrue(runs.stream().anyMatch(r -> r.code() && r.text().equals("a<b")));
        assertTrue(runs.stream().anyMatch(r -> "x.md".equals(r.link()) && r.text().equals("go")));
        assertTrue(runs.getLast().text().endsWith("& done"));
    }

    @Test
    void softWrappedLinesJoinIntoOneParagraph() {
        List<Row> rows = parse("first line\nsecond line\n\nnext").rows();

        assertEquals(2, rows.size());
        assertEquals("first line second line", text(((Paragraph) rows.getFirst()).runs()));
    }

    @Test
    void taskTreesKeepConnectorsAndCheckedState() {
        List<Row> rows = parse("- [x] done\n- [ ] open\n  - [ ] child\n").rows();
        ListItem done = (ListItem) rows.get(0);
        ListItem child = (ListItem) rows.get(2);

        assertTrue(done.taskTree() && done.task() && done.checked());
        assertEquals("done", text(done.runs()));
        assertEquals(1, child.depth());
        assertEquals("└─ ", child.connector());
        assertFalse(child.checked());
    }

    @Test
    void plainListsUseBulletsAndOrdinals() {
        List<Row> rows = parse("- a\n- b\n\n1. one\n2. two\n").rows();

        assertEquals("•", ((ListItem) rows.get(0)).prefix());
        assertEquals("1.", ((ListItem) rows.get(2)).prefix());
        assertFalse(((ListItem) rows.get(0)).taskTree());
    }

    @Test
    void tablesShareColumnLayout() {
        List<Row> rows = parse("| A | B |\n|---|---:|\n| 1 | 2 |\n| 3 | 4 |\n").rows();
        TableRow header = (TableRow) rows.get(0);
        TableRow last = (TableRow) rows.get(2);

        assertEquals(3, rows.size());
        assertTrue(header.header());
        assertTrue(last.last());
        assertEquals(Blocks.Alignment.RIGHT, header.table().alignment(1));
        assertEquals(100, header.table().widthPercent(0) + header.table().widthPercent(1), 0.01);
    }

    @Test
    void fencedCodeBecomesOneRowPerLineWithLazyTokens() {
        Blocks.Model model = parse("```java\nclass A {}\n\nint x;\n```\n");
        List<Row> rows = model.rows();

        assertEquals(3, rows.size());
        CodeLine first = (CodeLine) rows.getFirst();
        assertEquals(3, first.block().lineCount());
        assertFalse(first.block().tokens(0, first.text()).isEmpty());
        assertTrue(((CodeLine) rows.get(1)).block().tokens(1, "").isEmpty());
        model.close();
    }

    @Test
    void tablePaddingIsBoundedAndMatchesTheRenderer() {
        String markdown = "|a".repeat(128) + "|\n" + "|---".repeat(128) + "|\n"
                + "|\n".repeat(50_000);
        List<Row> rows = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> parse(markdown).rows());
        List<TableRow> tableRows = rows.stream()
                .filter(TableRow.class::isInstance).map(TableRow.class::cast).toList();
        long cells = tableRows.stream().mapToLong(row -> row.cells().size()).sum();
        String body = SafeMarkupRenderer.markdownBody(markdown);

        assertTrue(cells < markdown.length(), "cells stay linear: " + cells);
        assertEquals(body.split("<tr>", -1).length - 1, tableRows.size());
        assertTrue(tableRows.getLast().last());
    }

    @Test
    void blankRunsInsideAListStayLinear() {
        String markdown = "- a\n" + "\n".repeat(200_000) + "- b\n";
        List<Row> rows = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> parse(markdown).rows());

        assertEquals(2, rows.size());
        assertTrue(((ListItem) rows.getLast()).lastInList());
    }

    @Test
    void highlightedLinesStayHighlightedOnceTheBudgetIsSpent() {
        String[] lines = {"int answer = 42;"};
        HighlightLanguage language = HighlightLanguage.forMarkdownFence("```java");
        var budget = new HighlightBudget(1);
        try (var block = new Blocks.CodeBlock(
                SafeMarkupRenderer.fencedCodeDocument(lines, 0, 1, language.kind()),
                language, HighlightEngines.VANILLA, 1, true, budget)) {
            List<HighlightToken> first = block.tokens(0, lines[0]);

            assertTrue(budget.exhausted());
            assertTrue(first.stream().anyMatch(t -> t.role() != HighlightRole.PLAIN));
            assertEquals(first, block.tokens(0, lines[0]));
        }
    }

    @Test
    void quotesNestAndRulesStandAlone() {
        List<Row> rows = parse("> quoted\n> more\n\n---\n").rows();

        assertEquals(1, rows.getFirst().quoteDepth());
        assertInstanceOf(Rule.class, rows.getLast());
        assertNull(rows.stream().filter(Heading.class::isInstance).findFirst().orElse(null));
    }

    private static Blocks.Model parse(String markdown) {
        return Blocks.fromMarkdown(markdown, HighlightEngines.AUTOMATIC);
    }

    private static String text(List<Run> runs) {
        var out = new StringBuilder();
        for (Run run : runs) out.append(run.text());
        return out.toString();
    }
}
