package io.github.ghosthack.docview;

import io.github.ghosthack.docview.SafeMarkupRenderer.ListBlock;
import io.github.ghosthack.docview.SafeMarkupRenderer.ListNode;
import io.github.ghosthack.docview.SafeMarkupRenderer.TableAlignment;
import io.github.ghosthack.docview.SafeMarkupRenderer.TableHeader;
import io.github.ghosthack.docview.SafeMarkupRenderer.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Flat, row-oriented document model shared by Markdown and HTML, built by
 * {@link #fromMarkdown} and {@link #fromHtml} and drawn by native views.
 *
 * <p>Parsing follows {@link SafeMarkupRenderer}'s Markdown grammar by driving
 * the same block helpers (lists, tasks, tables, soft-wrapped paragraphs) and
 * by reading inline formatting from {@code SafeMarkupRenderer.inline}'s small,
 * fixed HTML vocabulary, so both Readers agree on what a document contains.
 *
 * <p>Every row is short (one heading, paragraph, list item, table row or code
 * line), which lets a virtualized view build only the rows on screen. Code
 * syntax tokens are computed lazily per visible line.
 */
public final class Blocks {

    private Blocks() {
    }

    /**
     * A styled inline run. {@code emphasis} is the Reader's bold-italic
     * {@code em}; {@code italic} is plain italic (HTML {@code i}). {@code link}
     * is inert target metadata, or null.
     */
    public record Run(String text, boolean code, boolean strong,
                      boolean emphasis, String link, boolean italic) {

        public Run(String text, boolean code, boolean strong, boolean emphasis,
                   String link) {
            this(text, code, strong, emphasis, link, false);
        }
    }

    public sealed interface Row permits Heading, Paragraph, ListItem,
            CodeLine, TableRow, Rule {
        /** Nesting depth inside block quotes (0 outside any quote). */
        int quoteDepth();
    }

    /** {@code anchor} matches {@link MarkdownOutline}'s, or null inside quotes. */
    public record Heading(int level, List<Run> runs, String anchor,
                          int quoteDepth) implements Row {
    }

    public record Paragraph(List<Run> runs, int quoteDepth) implements Row {
    }

    /**
     * One list item. Task trees carry a box-drawing {@code connector} and a
     * checkbox {@code prefix}; plain lists a bullet or ordinal {@code prefix}.
     */
    public record ListItem(int depth, String connector, String prefix,
                           boolean task, boolean checked, boolean taskTree,
                           List<Run> runs, boolean lastInList,
                           int quoteDepth) implements Row {
    }

    public record CodeLine(CodeBlock block, int line, String text,
                           int quoteDepth) implements Row {
    }

    public record TableRow(TableBlock table, List<List<Run>> cells,
                           boolean header, boolean last, int quoteDepth)
            implements Row {
    }

    public record Rule(int quoteDepth) implements Row {
    }

    /** Horizontal alignment requested by a table column. */
    public enum Alignment {
        /** No explicit alignment (left for cells, centred for headers). */
        NONE,
        LEFT,
        CENTER,
        RIGHT
    }

    /** Column layout shared by all rows of one table. */
    public static final class TableBlock {
        private final List<TableAlignment> alignments;
        private final double[] widthPercents;
        private final double[] naturalEm;

        TableBlock(List<TableAlignment> alignments, double[] widthPercents) {
            this(alignments, widthPercents, null);
        }

        /**
         * {@code naturalEm} gives content-sized column widths in em for a
         * table that shrinks to fit (HTML); null means full width (Markdown).
         */
        TableBlock(List<TableAlignment> alignments, double[] widthPercents,
                   double[] naturalEm) {
            this.alignments = List.copyOf(alignments);
            this.widthPercents = widthPercents.clone();
            this.naturalEm = naturalEm == null ? null : naturalEm.clone();
        }

        /** Content-sized width of {@code column} in em, or -1 for full width. */
        public double naturalEm(int column) {
            return naturalEm == null ? -1 : naturalEm[column];
        }

        public double naturalTotalEm() {
            if (naturalEm == null) return -1;
            double total = 0;
            for (double width : naturalEm) total += width;
            return total;
        }

        public int columns() {
            return widthPercents.length;
        }

        /** Share of the table width, in percent, for {@code column}. */
        public double widthPercent(int column) {
            return widthPercents[column];
        }

        public Alignment alignment(int column) {
            return switch (alignments.get(column)) {
                case LEFT -> Alignment.LEFT;
                case CENTER -> Alignment.CENTER;
                case RIGHT -> Alignment.RIGHT;
                default -> Alignment.NONE;
            };
        }
    }

    /** A fenced code block whose syntax session opens on first use. */
    public static final class CodeBlock implements AutoCloseable {
        private final DocumentContent content;
        private final HighlightLanguage language;
        private final String enginePreference;
        private final int lineCount;
        private final boolean lineNumbers;
        private final HighlightBudget budget;
        private HighlightSession session;

        CodeBlock(DocumentContent content, HighlightLanguage language,
                  String enginePreference, int lineCount, boolean lineNumbers,
                  HighlightBudget budget) {
            this.content = content;
            this.language = language;
            this.enginePreference = enginePreference;
            this.lineCount = lineCount;
            this.lineNumbers = lineNumbers;
            this.budget = budget;
        }

        /** Fenced Markdown code shows a gutter; HTML {@code pre} does not. */
        public boolean lineNumbers() {
            return lineNumbers;
        }

        public int lineCount() {
            return lineCount;
        }

        /** Width of the line-number gutter in digits. */
        public int digits() {
            return Integer.toString(Math.max(1, lineCount)).length();
        }

        /** Syntax tokens for one line; the session caches earlier lines' state. */
        public synchronized List<HighlightToken> tokens(int line, String text) {
            if (text.isEmpty()) return List.of();
            if (budget.exhausted()) return HighlightEngines.plainLine(text);
            if (session == null) {
                session = HighlightEngines.open(enginePreference, content, language);
            }
            return budget.tokens(session, line, text);
        }

        @Override
        public synchronized void close() {
            if (session != null) session.close();
            session = null;
        }
    }

    /** Parsed rows plus the code blocks whose sessions {@link #close} releases. */
    public record Model(List<Row> rows, List<CodeBlock> codeBlocks)
            implements AutoCloseable {

        /** Row index of the heading with {@code anchor}, or -1. */
        public int rowOfAnchor(String anchor) {
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index) instanceof Heading heading
                        && anchor.equals(heading.anchor())) {
                    return index;
                }
            }
            return -1;
        }

        @Override
        public void close() {
            for (CodeBlock block : codeBlocks) block.close();
        }
    }

    /**
     * Parses Markdown into rows. Code fences are highlighted lazily with
     * {@code highlightEnginePreference} (see {@link HighlightEngines}).
     */
    public static Model fromMarkdown(String markdown, String highlightEnginePreference) {
        var rows = new ArrayList<Row>();
        var blocks = new ArrayList<CodeBlock>();
        parse(markdown, highlightEnginePreference, 0, new int[1], rows, blocks,
                HighlightBudget.perDocument());
        return new Model(List.copyOf(rows), List.copyOf(blocks));
    }

    /**
     * Parses HTML into rows through the same structural whitelist as
     * {@link SafeMarkupRenderer}: scripts, styles, attributes other than link
     * targets, images and embeds never reach the model.
     */
    public static Model fromHtml(String html) {
        return HtmlBlockParser.parse(html);
    }

    /** Mirrors {@code SafeMarkupRenderer.markdownBody}'s block loop. */
    private static void parse(String markdown, String engine, int quoteDepth,
                              int[] nextHeading, List<Row> rows,
                              List<CodeBlock> blocks, HighlightBudget budget) {
        String[] lines = markdown.split("\\R", -1);
        CodeBlock code = null;
        int codeLine = 0;
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            String leadingTrimmed = line.stripLeading();
            if (leadingTrimmed.startsWith("```")) {
                if (code != null) {
                    code = null;
                } else {
                    HighlightLanguage language =
                            HighlightLanguage.forMarkdownFence(leadingTrimmed);
                    int count = SafeMarkupRenderer.fencedCodeLineCount(
                            lines, lineIndex + 1);
                    code = new CodeBlock(SafeMarkupRenderer.fencedCodeDocument(
                            lines, lineIndex + 1, count, language.kind()),
                            language, engine, count, true, budget);
                    blocks.add(code);
                    codeLine = 0;
                }
                continue;
            }
            if (code != null) {
                rows.add(new CodeLine(code, codeLine++, line, quoteDepth));
                continue;
            }
            TableHeader table = SafeMarkupRenderer.tableHeader(lines, lineIndex);
            if (table != null) {
                List<List<String>> body = new ArrayList<>();
                int rowIndex = lineIndex + 2;
                while (rowIndex < lines.length
                        && SafeMarkupRenderer.isTableBodyRow(lines[rowIndex])) {
                    body.add(SafeMarkupRenderer.normalizeCells(
                            SafeMarkupRenderer.tableCells(lines[rowIndex]),
                            table.cells().size()));
                    rowIndex++;
                }
                var layout = new TableBlock(table.alignments(),
                        columnWidths(table.cells(), body));
                rows.add(new TableRow(layout, cellRuns(table.cells()), true,
                        body.isEmpty(), quoteDepth));
                for (int r = 0; r < body.size(); r++) {
                    rows.add(new TableRow(layout, cellRuns(body.get(r)), false,
                            r == body.size() - 1, quoteDepth));
                }
                lineIndex = rowIndex - 1;
                continue;
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            Matcher heading = SafeMarkupRenderer.HEADING.matcher(trimmed);
            if (heading.matches()) {
                String anchor = quoteDepth == 0
                        ? MarkdownOutline.anchor(nextHeading[0]++) : null;
                rows.add(new Heading(heading.group(1).length(),
                        runs(SafeMarkupRenderer.inline(heading.group(2))),
                        anchor, quoteDepth));
                continue;
            }
            if (trimmed.matches("[-*_]{3,}")) {
                rows.add(new Rule(quoteDepth));
                continue;
            }
            if (trimmed.startsWith(">") && quoteDepth < SafeMarkupRenderer.MAX_QUOTE_DEPTH) {
                var quoted = new StringBuilder(trimmed.substring(1).stripLeading());
                int nextLine = lineIndex + 1;
                while (nextLine < lines.length
                        && lines[nextLine].strip().startsWith(">")) {
                    quoted.append('\n').append(
                            lines[nextLine].strip().substring(1).stripLeading());
                    nextLine++;
                }
                parse(quoted.toString(), engine, quoteDepth + 1, nextHeading,
                        rows, blocks, budget);
                lineIndex = nextLine - 1;
                continue;
            }
            if (SafeMarkupRenderer.listMarker(line) != null) {
                ListBlock list = SafeMarkupRenderer.parseListBlock(lines, lineIndex);
                boolean taskTree = list.nodes().stream()
                        .anyMatch(ListNode::containsTask);
                appendList(rows, list.nodes(), taskTree, List.of(), 0, quoteDepth);
                lineIndex = list.lastLine();
            } else {
                var paragraph = SafeMarkupRenderer.collectInlineBlock(
                        lines, lineIndex, trimmed);
                rows.add(new Paragraph(runs(paragraph.html()), quoteDepth));
                lineIndex = paragraph.lastLine();
            }
        }
    }

    /** Mirrors {@code appendListGroups}/{@code appendListNode}. */
    private static void appendList(List<Row> rows, List<ListNode> nodes,
                                   boolean taskTree, List<Boolean> ancestorLast,
                                   int depth, int quoteDepth) {
        for (int index = 0; index < nodes.size(); index++) {
            ListNode node = nodes.get(index);
            boolean last = index == nodes.size() - 1;
            Task task = taskTree ? SafeMarkupRenderer.task(node.text()) : null;
            String prefix;
            if (task != null) {
                prefix = (node.ordered() ? node.marker() + " " : "")
                        + (task.checked() ? "☑︎" : "☐︎");
            } else {
                prefix = node.ordered() ? node.marker() : "•";
            }
            String connector = taskTree
                    ? SafeMarkupRenderer.connector(ancestorLast, depth, last) : "";
            rows.add(new ListItem(depth, connector, prefix, task != null,
                    task != null && task.checked(), taskTree,
                    runs(SafeMarkupRenderer.inline(
                            task == null ? node.text() : task.text())),
                    last && node.children().isEmpty(), quoteDepth));
            if (!node.children().isEmpty()) {
                List<Boolean> childAncestors = ancestorLast;
                if (taskTree) {
                    var next = new ArrayList<>(ancestorLast);
                    if (depth > 0) next.add(last);
                    childAncestors = List.copyOf(next);
                }
                appendList(rows, node.children(), taskTree, childAncestors,
                        depth + 1, quoteDepth);
            }
        }
    }

    private static double[] columnWidths(List<String> header, List<List<String>> body) {
        double[] weights = new double[header.size()];
        for (int column = 0; column < weights.length; column++) {
            weights[column] = SafeMarkupRenderer.tableCellWidth(header.get(column));
            for (List<String> row : body) {
                weights[column] = Math.max(weights[column],
                        SafeMarkupRenderer.tableCellWidth(row.get(column)));
            }
        }
        return SafeMarkupRenderer.balancedColumnWidths(weights);
    }

    private static List<List<Run>> cellRuns(List<String> cells) {
        var out = new ArrayList<List<Run>>(cells.size());
        for (String cell : cells) out.add(runs(SafeMarkupRenderer.inline(cell)));
        return List.copyOf(out);
    }

    /**
     * Converts {@code SafeMarkupRenderer.inline} output, which only ever uses
     * {@code code}, {@code strong}, {@code em}, a link {@code span} and the
     * five {@link SafeMarkupRenderer#escape} entities, into styled runs.
     */
    static List<Run> runs(String html) {
        var runs = new ArrayList<Run>();
        var text = new StringBuilder();
        boolean code = false;
        boolean strong = false;
        boolean emphasis = false;
        String link = null;
        int index = 0;
        while (index < html.length()) {
            char c = html.charAt(index);
            if (c == '<') {
                int end = html.indexOf('>', index);
                if (end < 0) {
                    text.append(html, index, html.length());
                    break;
                }
                String tag = html.substring(index + 1, end);
                boolean closing = tag.startsWith("/");
                String name = (closing ? tag.substring(1) : tag).split("[\\s>]", 2)[0];
                flush(runs, text, code, strong, emphasis, link);
                switch (name) {
                    case "code" -> code = !closing;
                    case "strong" -> strong = !closing;
                    case "em" -> emphasis = !closing;
                    case "span" -> link = closing ? null : attribute(tag, "data-link-location");
                    default -> { }
                }
                index = end + 1;
            } else if (c == '&') {
                int end = html.indexOf(';', index);
                String entity = end < 0 ? "" : html.substring(index, end + 1);
                String decoded = switch (entity) {
                    case "&amp;" -> "&";
                    case "&lt;" -> "<";
                    case "&gt;" -> ">";
                    case "&quot;" -> "\"";
                    case "&#39;" -> "'";
                    default -> null;
                };
                if (decoded == null) {
                    text.append(c);
                    index++;
                } else {
                    text.append(decoded);
                    index = end + 1;
                }
            } else {
                text.append(c);
                index++;
            }
        }
        flush(runs, text, code, strong, emphasis, link);
        return List.copyOf(runs);
    }

    private static void flush(List<Run> runs, StringBuilder text, boolean code,
                              boolean strong, boolean emphasis, String link) {
        if (text.isEmpty()) return;
        runs.add(new Run(text.toString(), code, strong, emphasis, link));
        text.setLength(0);
    }

    private static String attribute(String tag, String name) {
        String marker = name + "=\"";
        int start = tag.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = tag.indexOf('"', start);
        if (end < 0) return null;
        List<Run> decoded = runs(tag.substring(start, end));
        return decoded.isEmpty() ? "" : decoded.getFirst().text();
    }
}
