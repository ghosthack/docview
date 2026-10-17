package io.github.ghosthack.docview;

import io.github.ghosthack.docview.Blocks.CodeBlock;
import io.github.ghosthack.docview.Blocks.CodeLine;
import io.github.ghosthack.docview.Blocks.Heading;
import io.github.ghosthack.docview.Blocks.ListItem;
import io.github.ghosthack.docview.Blocks.Model;
import io.github.ghosthack.docview.Blocks.Paragraph;
import io.github.ghosthack.docview.Blocks.Row;
import io.github.ghosthack.docview.Blocks.Rule;
import io.github.ghosthack.docview.Blocks.Run;
import io.github.ghosthack.docview.Blocks.TableBlock;
import io.github.ghosthack.docview.Blocks.TableRow;
import io.github.ghosthack.docview.SafeMarkupRenderer.TableAlignment;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/**
 * Builds the native Reader's row model from HTML through the same structural
 * whitelist as {@link SafeMarkupRenderer#htmlBody}: the same raw-text
 * stripping, the same Swing parser, and only the allowed tags. Scripts,
 * styles, attributes other than link targets, images and embeds never reach
 * the model. {@code u}, {@code s}, {@code sub} and {@code sup} keep their text
 * without styling.
 */
final class HtmlBlockParser {

    private HtmlBlockParser() {
    }

    static Model parse(String html) {
        var input = new SafeMarkupRenderer.StoppableReader(
                SafeMarkupRenderer.stripSuppressedRawTextElements(html));
        var builder = new Builder(input);
        try {
            new ParserDelegator().parse(input, builder, true);
        } catch (IOException impossibleForStringReader) {
            // StringReader does not throw.
        }
        if (input.stopped()) {
            // Too deeply nested for Swing's parser to recover in linear time;
            // show the source as plain lines, like the WebView page does.
            builder = new Builder(input);
            builder.pre = new StringBuilder(html);
            builder.flushPre();
        }
        builder.finish();
        return new Model(List.copyOf(builder.rows), List.copyOf(builder.codeBlocks));
    }

    private static final class Builder extends HTMLEditorKit.ParserCallback {
        final List<Row> rows = new ArrayList<>();
        final List<CodeBlock> codeBlocks = new ArrayList<>();
        private final List<Run> runs = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        /** Open lists: true for {@code ol}; with each list's next ordinal. */
        private final ArrayDeque<Boolean> lists = new ArrayDeque<>();
        private final ArrayDeque<Integer> ordinals = new ArrayDeque<>();
        private int suppressedDepth;
        private int quoteDepth;
        private int strong;
        private int emphasis;
        private int italic;
        private int code;
        private String link;
        private boolean previousEndedSpan;
        /** Block kind the pending inline text will become. */
        private int headingLevel;
        private String listPrefix;
        private int listDepth;
        private StringBuilder pre;
        private List<List<List<Run>>> tableRows;
        private List<Boolean> tableHeaders;
        private List<List<Run>> tableCells;
        private boolean rowHasHeader;
        /** Tables opened inside an open table; their text joins the outer cell. */
        private int nestedTables;
        private final SafeMarkupRenderer.StoppableReader input;
        /** The parser's open elements, implied ones included. */
        private int depth;

        Builder(SafeMarkupRenderer.StoppableReader input) {
            this.input = input;
        }

        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
            if (++depth > SafeMarkupRenderer.MAX_HTML_DEPTH) input.stop();
            if (input.stopped()) return;
            if (SafeMarkupRenderer.SUPPRESSED.contains(tag)) {
                suppressedDepth++;
                return;
            }
            if (suppressedDepth > 0 || !SafeMarkupRenderer.ALLOWED.contains(tag)) return;
            if (tag == HTML.Tag.SPAN && previousEndedSpan) appendText(" ");
            previousEndedSpan = false;
            if (tableRows != null && (tag == HTML.Tag.TABLE || nestedTables > 0)) {
                // A table nested in a cell is flattened into that cell's text.
                if (tag == HTML.Tag.TABLE) nestedTables++;
                if (tag == HTML.Tag.TR || tag == HTML.Tag.TD || tag == HTML.Tag.TH) {
                    appendText(" ");
                }
                return;
            }
            if (tag == HTML.Tag.H1 || tag == HTML.Tag.H2 || tag == HTML.Tag.H3
                    || tag == HTML.Tag.H4 || tag == HTML.Tag.H5 || tag == HTML.Tag.H6) {
                flushBlock();
                // A heading in a table cell is plain cell text.
                if (tableCells == null) headingLevel = tag.toString().charAt(1) - '0';
            } else if (tag == HTML.Tag.P || tag == HTML.Tag.DIV
                    || tag == HTML.Tag.CAPTION) {
                flushBlock();
            } else if (tag == HTML.Tag.BLOCKQUOTE) {
                flushBlock();
                quoteDepth++;
            } else if (tag == HTML.Tag.PRE) {
                flushBlock();
                pre = new StringBuilder();
            } else if (tag == HTML.Tag.UL || tag == HTML.Tag.OL) {
                flushBlock();
                lists.push(tag == HTML.Tag.OL);
                ordinals.push(1);
            } else if (tag == HTML.Tag.LI) {
                flushBlock();
                boolean ordered = !lists.isEmpty() && lists.peek();
                int ordinal = ordinals.isEmpty() ? 1 : ordinals.pop();
                if (!lists.isEmpty()) ordinals.push(ordinal + 1);
                if (tableCells == null) { // a list item in a cell is plain cell text
                    listPrefix = ordered ? ordinal + "." : "•";
                    listDepth = Math.max(0, lists.size() - 1);
                }
            } else if (tag == HTML.Tag.TABLE) {
                flushBlock();
                tableRows = new ArrayList<>();
                tableHeaders = new ArrayList<>();
            } else if (tag == HTML.Tag.TR) {
                flushBlock();
                tableCells = new ArrayList<>();
                rowHasHeader = false;
            } else if (tag == HTML.Tag.TH || tag == HTML.Tag.TD) {
                flushBlock();
                if (tag == HTML.Tag.TH) rowHasHeader = true;
            } else if (tag == HTML.Tag.STRONG || tag == HTML.Tag.B) {
                flushRun();
                strong++;
            } else if (tag == HTML.Tag.EM) {
                flushRun();
                emphasis++;
            } else if (tag == HTML.Tag.I) {
                flushRun();
                italic++;
            } else if (tag == HTML.Tag.CODE) {
                flushRun();
                code++;
            } else if (tag == HTML.Tag.A) {
                flushRun();
                Object target = attrs.getAttribute(HTML.Attribute.HREF);
                link = target == null ? null : target.toString();
            }
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int pos) {
            depth--;
            if (input.stopped()) return;
            if (SafeMarkupRenderer.SUPPRESSED.contains(tag)) {
                if (suppressedDepth > 0) suppressedDepth--;
                return;
            }
            if (suppressedDepth > 0 || !SafeMarkupRenderer.ALLOWED.contains(tag)) return;
            if (nestedTables > 0) {
                // Start tags inside a nested table were ignored; ignore their
                // end tags too so they cannot change the outer state.
                if (tag == HTML.Tag.TABLE) nestedTables--;
                return;
            }
            if (tag == HTML.Tag.STRONG || tag == HTML.Tag.B) {
                flushRun();
                strong = Math.max(0, strong - 1);
            } else if (tag == HTML.Tag.EM) {
                flushRun();
                emphasis = Math.max(0, emphasis - 1);
            } else if (tag == HTML.Tag.I) {
                flushRun();
                italic = Math.max(0, italic - 1);
            } else if (tag == HTML.Tag.CODE) {
                flushRun();
                code = Math.max(0, code - 1);
            } else if (tag == HTML.Tag.A) {
                flushRun();
                link = null;
            } else if (tag == HTML.Tag.PRE) {
                flushPre();
            } else if (tag == HTML.Tag.BLOCKQUOTE) {
                flushBlock();
                quoteDepth = Math.max(0, quoteDepth - 1);
            } else if (tag == HTML.Tag.UL || tag == HTML.Tag.OL) {
                flushBlock();
                if (tableCells == null) listPrefix = null;
                if (!lists.isEmpty()) lists.pop();
                if (!ordinals.isEmpty()) ordinals.pop();
                if (lists.isEmpty()) markListEnd();
            } else if (tag == HTML.Tag.LI) {
                flushBlock();
                if (tableCells == null) listPrefix = null; // even when it had no text
            } else if (tag == HTML.Tag.TH || tag == HTML.Tag.TD) {
                if (tableCells != null) {
                    flushRun();
                    tableCells.add(trimRuns(List.copyOf(runs)));
                    runs.clear();
                }
            } else if (tag == HTML.Tag.TR) {
                if (tableRows != null && tableCells != null && !tableCells.isEmpty()) {
                    tableRows.add(tableCells);
                    tableHeaders.add(rowHasHeader);
                }
                tableCells = null;
            } else if (tag == HTML.Tag.TABLE) {
                flushTable();
            } else if (tag != HTML.Tag.SPAN && tag != HTML.Tag.U && tag != HTML.Tag.S
                    && tag != HTML.Tag.SUB && tag != HTML.Tag.SUP) {
                flushBlock();
            }
            previousEndedSpan = tag == HTML.Tag.SPAN;
        }

        @Override
        public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
            if (input.stopped()) return;
            if (suppressedDepth > 0 || !SafeMarkupRenderer.ALLOWED.contains(tag)) return;
            previousEndedSpan = false;
            if (tag == HTML.Tag.HR) {
                flushBlock();
                rows.add(new Rule(quoteDepth));
            } else if (tag == HTML.Tag.BR) {
                if (pre != null) pre.append('\n');
                else appendText("\n");
            }
        }

        @Override
        public void handleText(char[] data, int pos) {
            if (suppressedDepth > 0 || input.stopped()) return;
            previousEndedSpan = false;
            String value = new String(data);
            if (pre != null) {
                pre.append(value);
            } else {
                appendText(value.replaceAll("[ \\t\\r\\n\\f]+", " "));
            }
        }

        void finish() {
            nestedTables = 0; // an unclosed nested table ends with the input
            if (pre != null) flushPre();
            if (tableRows != null) flushTable();
            flushBlock();
        }

        private void appendText(String value) {
            if (value.equals(" ") && text.isEmpty() && runs.isEmpty()) return;
            text.append(value);
        }

        private void flushRun() {
            if (text.isEmpty()) return;
            runs.add(new Run(text.toString(), code > 0, strong > 0, emphasis > 0, link,
                    italic > 0));
            text.setLength(0);
        }

        /**
         * Ends the pending inline text as a list item, heading or paragraph. An
         * open {@code li} keeps its bullet until it has text, so blocks nested
         * in it ({@code <li><p>..</p></li>}) still become list items.
         */
        private void flushBlock() {
            flushRun();
            if (tableCells != null) {
                // A cell collects runs until it closes; its block children
                // are separated by a space so their words do not run together.
                String last = runs.isEmpty() ? "" : runs.getLast().text();
                if (!last.isEmpty()
                        && !Character.isWhitespace(last.charAt(last.length() - 1))) {
                    text.append(' ');
                }
                return;
            }
            List<Run> block = trimRuns(List.copyOf(runs));
            runs.clear();
            if (!block.isEmpty()) {
                if (listPrefix != null) {
                    rows.add(new ListItem(listDepth, "", listPrefix, false, false, false,
                            block, false, quoteDepth));
                } else if (headingLevel > 0) {
                    rows.add(new Heading(headingLevel, block, null, quoteDepth));
                } else {
                    rows.add(new Paragraph(block, quoteDepth));
                }
                listPrefix = null;
            }
            headingLevel = 0;
        }

        /** Gives the last item of a top-level list its trailing spacing. */
        private void markListEnd() {
            if (!rows.isEmpty() && rows.getLast() instanceof ListItem item
                    && !item.lastInList()) {
                rows.set(rows.size() - 1, new ListItem(item.depth(), item.connector(),
                        item.prefix(), item.task(), item.checked(), item.taskTree(),
                        item.runs(), true, item.quoteDepth()));
            }
        }

        private void flushPre() {
            if (pre == null) return;
            String body = pre.toString();
            pre = null;
            if (tableCells != null) {
                // Preformatted text inside a table cell stays in that cell.
                flushRun();
                String flat = body.strip().replaceAll("\\s*\\R\\s*", " ");
                if (!flat.isEmpty()) runs.add(new Run(flat, true, false, false, link));
                return;
            }
            flushRun();
            runs.clear();
            listPrefix = null; // code lines have no bullet; later text is no item
            if (body.startsWith("\n")) body = body.substring(1);
            if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
            String[] lines = body.split("\\R", -1);
            var language = HighlightLanguage.forMarkdownFence("```");
            var block = new CodeBlock(SafeMarkupRenderer.fencedCodeDocument(
                    lines, 0, lines.length, language.kind()),
                    language, HighlightEngines.NONE, lines.length, false,
                    HighlightBudget.perDocument());
            codeBlocks.add(block);
            for (int line = 0; line < lines.length; line++) {
                rows.add(new CodeLine(block, line, lines[line], quoteDepth));
            }
        }

        private void flushTable() {
            List<List<List<Run>>> body = tableRows;
            List<Boolean> headers = tableHeaders;
            tableRows = null;
            tableHeaders = null;
            tableCells = null;
            if (body == null || body.isEmpty()) return;
            int columns = 0;
            for (List<List<Run>> row : body) columns = Math.max(columns, row.size());
            double[] weights = new double[columns];
            for (List<List<Run>> row : body) {
                for (int column = 0; column < row.size(); column++) {
                    weights[column] = Math.max(weights[column],
                            SafeMarkupRenderer.tableCellWidth(plain(row.get(column))));
                }
            }
            // HTML tables shrink to their content like WebKit's auto layout:
            // roughly 0.62em per character (generous for digits and bold
            // headers) plus the cell's 1.2em padding and borders.
            double[] natural = new double[columns];
            for (int column = 0; column < columns; column++) {
                natural[column] = weights[column] * 0.62 + 1.8;
            }
            var layout = new TableBlock(Collections.nCopies(columns, TableAlignment.NONE),
                    SafeMarkupRenderer.balancedColumnWidths(weights), natural);
            for (int index = 0; index < body.size(); index++) {
                var cells = new ArrayList<>(body.get(index));
                while (cells.size() < columns) cells.add(List.of());
                rows.add(new TableRow(layout, List.copyOf(cells), headers.get(index),
                        index == body.size() - 1, quoteDepth));
            }
        }

        private static String plain(List<Run> runs) {
            var out = new StringBuilder();
            for (Run run : runs) out.append(run.text());
            return out.toString();
        }

        /** Drops collapsed whitespace at the block's edges. */
        private static List<Run> trimRuns(List<Run> block) {
            var trimmed = new ArrayList<Run>(block.size());
            for (Run run : block) {
                String value = run.text();
                if (trimmed.isEmpty()) value = value.stripLeading();
                if (!value.isEmpty()) {
                    trimmed.add(new Run(value, run.code(), run.strong(), run.emphasis(),
                            run.link(), run.italic()));
                }
            }
            while (!trimmed.isEmpty()) {
                Run last = trimmed.getLast();
                String value = last.text().stripTrailing();
                if (!value.isEmpty()) {
                    trimmed.set(trimmed.size() - 1, new Run(value, last.code(),
                            last.strong(), last.emphasis(), last.link(), last.italic()));
                    break;
                }
                trimmed.removeLast();
            }
            return List.copyOf(trimmed);
        }
    }
}
