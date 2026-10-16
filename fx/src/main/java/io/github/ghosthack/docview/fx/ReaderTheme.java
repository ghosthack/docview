package io.github.ghosthack.docview.fx;

import io.github.ghosthack.docview.HighlightRole;
import io.github.ghosthack.docview.HighlightTheme;

import javafx.scene.paint.Color;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Colors for {@link BlockView} and {@link XmlTreeView}. Start from
 * {@link #dark()} or {@link #light()} and adjust with {@link #toBuilder()}:
 *
 * <pre>{@code
 * ReaderTheme theme = ReaderTheme.light().toBuilder()
 *         .link(Color.web("#b0004f"))
 *         .syntax(HighlightRole.KEYWORD, Color.web("#7a00b0"))
 *         .build();
 * view.setTheme(theme);
 * }</pre>
 *
 * Instances are immutable and safe to share between views.
 */
public final class ReaderTheme {

    private final Color background;
    private final Color text;
    private final Color heading;
    private final Color rule;
    private final Color codeBackground;
    private final Color link;
    private final Color quoteBar;
    private final Color quoteText;
    private final Color tableBorder;
    private final Color lineNumber;
    private final Color connector;
    private final Color selection;
    private final Color selectedCode;
    private final Color xmlTag;
    private final Color xmlKey;
    private final Color xmlValue;
    private final Color muted;
    private final Color pillBackground;
    private final Color pillBorder;
    private final Color headerText;
    private final Color divider;
    private final Map<HighlightRole, Color> syntax;

    private ReaderTheme(Builder b) {
        background = b.background;
        text = b.text;
        heading = b.heading;
        rule = b.rule;
        codeBackground = b.codeBackground;
        link = b.link;
        quoteBar = b.quoteBar;
        quoteText = b.quoteText;
        tableBorder = b.tableBorder;
        lineNumber = b.lineNumber;
        connector = b.connector;
        selection = b.selection;
        selectedCode = b.selectedCode;
        xmlTag = b.xmlTag;
        xmlKey = b.xmlKey;
        xmlValue = b.xmlValue;
        muted = b.muted;
        pillBackground = b.pillBackground;
        pillBorder = b.pillBorder;
        headerText = b.headerText;
        divider = b.divider;
        syntax = Map.copyOf(b.syntax);
    }

    /** The default dark palette, matching docview's WebView pages. */
    public static ReaderTheme dark() {
        var builder = new Builder()
                .background(Color.web("#111111")).text(Color.web("#dddddd"))
                .heading(Color.web("#f2f2f2")).rule(Color.web("#383838"))
                .codeBackground(Color.web("#1b1b1b")).link(Color.web("#7db9ff"))
                .quoteBar(Color.web("#666666")).quoteText(Color.web("#bbbbbb"))
                .tableBorder(Color.web("#555555")).lineNumber(Color.web("#707070"))
                .connector(Color.web("#777777", 0.5)).selection(Color.web("#3a6ea5", 0.6))
                .selectedCode(Color.web("#2f5680")).xmlTag(Color.web("#83c9ff"))
                .xmlKey(Color.web("#d7ba7d")).xmlValue(Color.web("#b5cea8"))
                .muted(Color.web("#707070")).pillBackground(Color.web("#171717"))
                .pillBorder(Color.web("#444444")).headerText(Color.web("#858585"))
                .divider(Color.web("#444444"));
        for (HighlightRole role : HighlightRole.values()) {
            builder.syntax(role, Color.web(HighlightTheme.color(role)));
        }
        return builder.build();
    }

    /** A light palette for bright surroundings. */
    public static ReaderTheme light() {
        return new Builder()
                .background(Color.web("#fcfcfc")).text(Color.web("#222222"))
                .heading(Color.web("#111111")).rule(Color.web("#dddddd"))
                .codeBackground(Color.web("#f3f3f3")).link(Color.web("#0b62c4"))
                .quoteBar(Color.web("#c8c8c8")).quoteText(Color.web("#555555"))
                .tableBorder(Color.web("#c8c8c8")).lineNumber(Color.web("#999999"))
                .connector(Color.web("#888888", 0.6)).selection(Color.web("#9cc3f0", 0.6))
                .selectedCode(Color.web("#b6d2f3")).xmlTag(Color.web("#0b5cad"))
                .xmlKey(Color.web("#8a5a00")).xmlValue(Color.web("#2f6f2f"))
                .muted(Color.web("#999999")).pillBackground(Color.web("#f5f5f5"))
                .pillBorder(Color.web("#cccccc")).headerText(Color.web("#777777"))
                .divider(Color.web("#dddddd"))
                .syntax(HighlightRole.PLAIN, Color.web("#222222"))
                .syntax(HighlightRole.COMMENT, Color.web("#6a737d"))
                .syntax(HighlightRole.STRING, Color.web("#a3501d"))
                .syntax(HighlightRole.NUMBER, Color.web("#005cc5"))
                .syntax(HighlightRole.KEYWORD, Color.web("#8a2bb9"))
                .syntax(HighlightRole.TYPE, Color.web("#0b5cad"))
                .syntax(HighlightRole.KEY, Color.web("#00796b"))
                .syntax(HighlightRole.TAG, Color.web("#c0392b"))
                .syntax(HighlightRole.HEADING, Color.web("#a05a00"))
                .syntax(HighlightRole.EMPHASIS, Color.web("#2f7d32"))
                .syntax(HighlightRole.LINK, Color.web("#0b62c4"))
                .build();
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.background = background;
        b.text = text;
        b.heading = heading;
        b.rule = rule;
        b.codeBackground = codeBackground;
        b.link = link;
        b.quoteBar = quoteBar;
        b.quoteText = quoteText;
        b.tableBorder = tableBorder;
        b.lineNumber = lineNumber;
        b.connector = connector;
        b.selection = selection;
        b.selectedCode = selectedCode;
        b.xmlTag = xmlTag;
        b.xmlKey = xmlKey;
        b.xmlValue = xmlValue;
        b.muted = muted;
        b.pillBackground = pillBackground;
        b.pillBorder = pillBorder;
        b.headerText = headerText;
        b.divider = divider;
        b.syntax.putAll(syntax);
        return b;
    }

    public Color background() { return background; }
    public Color text() { return text; }
    public Color heading() { return heading; }
    public Color rule() { return rule; }
    public Color codeBackground() { return codeBackground; }
    public Color link() { return link; }
    public Color quoteBar() { return quoteBar; }
    public Color quoteText() { return quoteText; }
    public Color tableBorder() { return tableBorder; }
    public Color lineNumber() { return lineNumber; }
    public Color connector() { return connector; }
    public Color selection() { return selection; }
    /** Background of an inline code chip inside the selection. */
    public Color selectedCode() { return selectedCode; }
    public Color xmlTag() { return xmlTag; }
    /** XML attribute names and pill keys. */
    public Color xmlKey() { return xmlKey; }
    /** XML attribute values and pill values. */
    public Color xmlValue() { return xmlValue; }
    /** Empty values, limit rows and secondary controls. */
    public Color muted() { return muted; }
    public Color pillBackground() { return pillBackground; }
    public Color pillBorder() { return pillBorder; }
    /** Column headers and control text. */
    public Color headerText() { return headerText; }
    /** Separators between the XML controls, header and rows. */
    public Color divider() { return divider; }

    /** Color for a syntax token role; plain text when the role has none. */
    public Color syntax(HighlightRole role) {
        Color color = syntax.get(role);
        return color != null ? color : syntax.getOrDefault(HighlightRole.PLAIN, text);
    }

    /** CSS hex form ({@code #rrggbb} or {@code #rrggbbaa}) used for stylesheets. */
    static String css(Color color) {
        int r = (int) Math.round(color.getRed() * 255);
        int g = (int) Math.round(color.getGreen() * 255);
        int b = (int) Math.round(color.getBlue() * 255);
        int a = (int) Math.round(color.getOpacity() * 255);
        return a == 255 ? String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b)
                : String.format(Locale.ROOT, "#%02x%02x%02x%02x", r, g, b, a);
    }

    /** Mutable builder; every color must be set (presets set them all). */
    public static final class Builder {
        private Color background;
        private Color text;
        private Color heading;
        private Color rule;
        private Color codeBackground;
        private Color link;
        private Color quoteBar;
        private Color quoteText;
        private Color tableBorder;
        private Color lineNumber;
        private Color connector;
        private Color selection;
        private Color selectedCode;
        private Color xmlTag;
        private Color xmlKey;
        private Color xmlValue;
        private Color muted;
        private Color pillBackground;
        private Color pillBorder;
        private Color headerText;
        private Color divider;
        private final Map<HighlightRole, Color> syntax = new EnumMap<>(HighlightRole.class);

        private Builder() {
        }

        public Builder background(Color c) { background = c; return this; }
        public Builder text(Color c) { text = c; return this; }
        public Builder heading(Color c) { heading = c; return this; }
        public Builder rule(Color c) { rule = c; return this; }
        public Builder codeBackground(Color c) { codeBackground = c; return this; }
        public Builder link(Color c) { link = c; return this; }
        public Builder quoteBar(Color c) { quoteBar = c; return this; }
        public Builder quoteText(Color c) { quoteText = c; return this; }
        public Builder tableBorder(Color c) { tableBorder = c; return this; }
        public Builder lineNumber(Color c) { lineNumber = c; return this; }
        public Builder connector(Color c) { connector = c; return this; }
        public Builder selection(Color c) { selection = c; return this; }
        public Builder selectedCode(Color c) { selectedCode = c; return this; }
        public Builder xmlTag(Color c) { xmlTag = c; return this; }
        public Builder xmlKey(Color c) { xmlKey = c; return this; }
        public Builder xmlValue(Color c) { xmlValue = c; return this; }
        public Builder muted(Color c) { muted = c; return this; }
        public Builder pillBackground(Color c) { pillBackground = c; return this; }
        public Builder pillBorder(Color c) { pillBorder = c; return this; }
        public Builder headerText(Color c) { headerText = c; return this; }
        public Builder divider(Color c) { divider = c; return this; }

        public Builder syntax(HighlightRole role, Color c) {
            syntax.put(Objects.requireNonNull(role), Objects.requireNonNull(c));
            return this;
        }

        public ReaderTheme build() {
            for (Color c : new Color[]{background, text, heading, rule, codeBackground, link,
                    quoteBar, quoteText, tableBorder, lineNumber, connector, selection,
                    selectedCode, xmlTag, xmlKey, xmlValue, muted, pillBackground, pillBorder,
                    headerText, divider}) {
                Objects.requireNonNull(c, "every ReaderTheme color must be set");
            }
            return new ReaderTheme(this);
        }
    }
}
