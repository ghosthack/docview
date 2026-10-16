package io.github.ghosthack.docview;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the headings shared by Markdown Reader rendering and its contents tree. */
public final class MarkdownOutline {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern LINK = Pattern.compile("!?\\[([^]]+)]\\([^)]*\\)");
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
     * Returns ATX headings outside fenced code blocks, in document order.
     * This deliberately follows Reader's small Markdown dialect rather than
     * trying to recognize constructs that Reader itself does not render.
     */
    public static List<Heading> headings(String markdown) {
        if (markdown == null || markdown.isEmpty()) return List.of();
        String[] lines = markdown.split("\\R", -1);
        var headings = new ArrayList<Heading>();
        boolean inCode = false;
        for (String line : lines) {
            String trimmed = line.strip();
            if (line.stripLeading().startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            if (inCode) continue;
            Matcher match = HEADING.matcher(trimmed);
            if (!match.matches()) continue;
            headings.add(new Heading(
                    match.group(1).length(), displayText(match.group(2)),
                    anchor(headings.size())));
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
