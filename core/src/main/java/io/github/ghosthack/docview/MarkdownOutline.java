package io.github.ghosthack.docview;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.ghosthack.docview.SafeMarkupRenderer.TableHeader;

/** Extracts the headings shared by Markdown Reader rendering and its contents tree. */
public final class MarkdownOutline {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    // Same linear link shape as SafeMarkupRenderer: no brackets in the label
    // and no parentheses in the target, so each attempt stops early.
    private static final Pattern LINK = Pattern.compile(
            "!?\\[([^\\[\\]]+)]\\(([^()]*)\\)");
    private static final Pattern CODE = Pattern.compile("`([^`]*)`");
    private static final Pattern STRONG =
            Pattern.compile("\\*\\*([^*]+)\\*\\*|__([^_]+)__");
    private static final Pattern EMPHASIS = Pattern.compile(
            "(?<!\\*)\\*([^*]+)\\*(?!\\*)|(?<!_)_([^_]+)_(?!_)");

    private MarkdownOutline() {
    }

    /** A visible heading and the stable DOM anchor emitted for it by Reader. */
    public record Heading(int level, String text, String anchor) {
    }

    /**
     * Returns ATX headings outside fenced code blocks, tables and quotes, in
     * document order.
     * This deliberately follows Reader's small Markdown dialect rather than
     * trying to recognize constructs that Reader itself does not render.
     */
    public static List<Heading> headings(String markdown) {
        if (markdown == null || markdown.isEmpty()) return List.of();
        String[] lines = markdown.split("\\R", -1);
        var headings = new ArrayList<Heading>();
        boolean inCode = false;
        // Mirrors the block loop of SafeMarkupRenderer and Blocks, so lines
        // they consume as table rows, quotes, lists or paragraphs never count
        // as headings here and anchors stay numbered alike.
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            String trimmed = line.strip();
            if (line.stripLeading().startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            if (inCode) continue;
            TableHeader table = SafeMarkupRenderer.tableHeader(lines, lineIndex);
            if (table != null) {
                lineIndex = SafeMarkupRenderer.tableBody(
                        lines, lineIndex, table.cells().size()).lastLine();
                continue;
            }
            if (trimmed.isEmpty()) continue;
            Matcher match = HEADING.matcher(trimmed);
            if (match.matches()) {
                headings.add(new Heading(
                        match.group(1).length(), displayText(match.group(2)),
                        anchor(headings.size())));
            } else if (trimmed.matches("[-*_]{3,}")) {
                continue;
            } else if (trimmed.startsWith(">")) {
                while (lineIndex + 1 < lines.length
                        && lines[lineIndex + 1].strip().startsWith(">")) {
                    lineIndex++;
                }
            } else if (SafeMarkupRenderer.listMarker(line) != null) {
                lineIndex = SafeMarkupRenderer.parseListBlock(lines, lineIndex).lastLine();
            } else {
                while (lineIndex + 1 < lines.length
                        && !SafeMarkupRenderer.startsMarkdownBlock(lines, lineIndex + 1)) {
                    lineIndex++;
                }
            }
        }
        return List.copyOf(headings);
    }

    public static String anchor(int headingIndex) {
        return "md-heading-" + headingIndex;
    }

    private static String displayText(String markdown) {
        String text = LINK.matcher(markdown).replaceAll("$1");
        text = CODE.matcher(text).replaceAll("$1");
        text = STRONG.matcher(text).replaceAll("$1$2");
        text = EMPHASIS.matcher(text).replaceAll("$1$2");
        return text.isBlank() ? "Untitled heading" : text.strip();
    }
}
