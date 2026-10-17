package io.github.ghosthack.docview;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Converts Markdown, HTML or XML into a self-contained, script-free Reader
 * page. Raw HTML is parsed and re-emitted through a small structural whitelist;
 * all attributes, URLs, embedded content, styles and scripts are discarded.
 */
public final class SafeMarkupRenderer {

    static final int MAX_XML_TREE_DEPTH = 128;
    static final int MAX_XML_TREE_NODES = 20_000;
    /** Contracted leaf pills per XML document; past it one "more" pill is shown. */
    static final int MAX_XML_COMPACT_ENTRIES = 20_000;
    /** Largest XML text parsed into a tree; the DOM is built before any cap applies. */
    static final int MAX_XML_TREE_CHARS = 16 * 1024 * 1024;
    /** Deepest nested block quote; deeper {@code >} lines read as paragraphs. */
    static final int MAX_QUOTE_DEPTH = 32;
    /** Raw text shown when XML cannot be shown as a tree, at most. */
    static final int MAX_FALLBACK_CHARS = 1024 * 1024;
    /** Deepest list nesting; deeper items attach at this depth. */
    static final int MAX_LIST_DEPTH = 32;
    /** Widest Markdown table; a wider header row is not read as a table. */
    static final int MAX_TABLE_COLUMNS = 128;
    /**
     * Deepest element stack handed to Swing's HTML parser, whose recovery
     * scans that stack for each misplaced tag; deeper HTML renders as source.
     */
    static final int MAX_HTML_DEPTH = 256;
    static final double XML_TREE_INDENT_EM = 1.84;
    static final double XML_MIN_TAG_COLUMN_EM = 16.0;
    private static final double XML_MIN_VALUE_COLUMN_EM = 28.0;

    static final Set<HTML.Tag> ALLOWED = Set.of(
            HTML.Tag.H1, HTML.Tag.H2, HTML.Tag.H3, HTML.Tag.H4, HTML.Tag.H5, HTML.Tag.H6,
            HTML.Tag.P, HTML.Tag.BR, HTML.Tag.PRE, HTML.Tag.CODE, HTML.Tag.EM,
            HTML.Tag.STRONG, HTML.Tag.BLOCKQUOTE, HTML.Tag.UL, HTML.Tag.OL,
            HTML.Tag.LI, HTML.Tag.HR, HTML.Tag.TABLE, HTML.Tag.TR, HTML.Tag.TH,
            HTML.Tag.TD, HTML.Tag.CAPTION, HTML.Tag.DIV, HTML.Tag.SPAN,
            HTML.Tag.B, HTML.Tag.I, HTML.Tag.U, HTML.Tag.S, HTML.Tag.SUB, HTML.Tag.SUP,
            HTML.Tag.A);
    static final Set<HTML.Tag> SUPPRESSED = Set.of(
            HTML.Tag.SCRIPT, HTML.Tag.STYLE, HTML.Tag.TITLE,
            HTML.Tag.OBJECT, HTML.Tag.APPLET);
    private static final Set<String> RAW_TEXT_SUPPRESSED = Set.of(
            "script", "style", "title");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern STRONG = Pattern.compile(
            "\\*\\*([^*]+)\\*\\*|"
                    + "(?<![\\p{L}\\p{N}_])__([^_]+)__(?![\\p{L}\\p{N}_])");
    private static final Pattern EMPHASIS = Pattern.compile(
            "(?<!\\*)\\*([^*]+)\\*(?!\\*)|"
                    + "(?<![\\p{L}\\p{N}_])_([^_]+)_(?![\\p{L}\\p{N}_])");
    // Label without brackets, target without parentheses: each match attempt
    // stops at the next bracket/paren, so scanning stays linear.
    private static final Pattern LINK = Pattern.compile(
            "!?\\[([^\\[\\]]+)]\\(([^()]*)\\)");
    /** Private-use sentinels marking protected spans inside {@link #inline}. */
    private static final char CODE_MARK = '\uE000';
    private static final char LINK_MARK = '\uE001';
    private static final char MARK_END = '\uE002';
    private static final Pattern TABLE_DELIMITER = Pattern.compile(":?-{3,}:?");
    static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^[-+*]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\d+[.)]\\s+(.*)$");
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^(\\s*)([-+*]|(\\d+[.)]))\\s+(.*)$");
    private static final Pattern UNCHECKED_TASK = Pattern.compile(
            "^\\[(?: )?\\](?:[ \\t]+(.*))?$");
    private static final Pattern CHECKED_TASK = Pattern.compile(
            "^\\[(?:x|check)\\](?:[ \\t]+(.*))?$",
            Pattern.CASE_INSENSITIVE);

    private SafeMarkupRenderer() {
    }

    public static String page(DocumentContent document, ReaderStyle style) {
        return page(document, style, HighlightEngines.AUTOMATIC);
    }

    public static String page(
            DocumentContent document, ReaderStyle style,
            String highlightEnginePreference) {
        String body = switch (document.kind()) {
            case MARKDOWN -> markdownBody(
                    document.text(), highlightEnginePreference);
            case HTML -> htmlBody(document.text());
            case XML -> xmlBody(document.text());
            default -> "<pre class=\"plain\">" + escape(document.text()) + "</pre>";
        };
        return wrap(webViewSafeBody(body), style,
                document.kind() == DocumentKind.XML);
    }

    public static String errorPage(String message, ReaderStyle style) {
        return wrap(webViewSafeBody(
                "<div class=\"error\">" + escape(message) + "</div>"),
                style, false);
    }

    /** Builds a read-only, expandable element tree without resolving external XML content. */
    /**
     * Parses well-formed XML into its root element with external entities,
     * external DTDs, schemas and XInclude disabled. Internal DTD entities are
     * expanded within explicit entity limits. Shared by the WebView and native
     * XML tree Readers so both stay equally locked down.
     */
    static Element parseXmlRoot(String xml)
            throws ParserConfigurationException, SAXException, IOException {
        // A decoded file keeps its BOM, which a character stream treats as content.
        if (!xml.isEmpty() && xml.charAt(0) == '\uFEFF') xml = xml.substring(1);
        if (xml.length() > MAX_XML_TREE_CHARS) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "The document is too large for the XML tree (%.1f MiB; limit %d MiB).",
                    xml.length() / 1048576.0, MAX_XML_TREE_CHARS / 1048576));
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        // Expanded so internal entity text and elements reach the tree.
        factory.setExpandEntityReferences(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        // Explicit limits, so the result does not depend on the running JDK's
        // defaults. Depth is left to the tree (MAX_XML_TREE_DEPTH) and the input
        // cap; entity expansion may not grow past what the input cap allows.
        factory.setAttribute("jdk.xml.maxElementDepth", "0");
        factory.setAttribute("jdk.xml.elementAttributeLimit", "10000");
        factory.setAttribute("jdk.xml.entityExpansionLimit", "64000");
        factory.setAttribute("jdk.xml.entityReplacementLimit", "3000000");
        factory.setAttribute("jdk.xml.totalEntitySizeLimit",
                Integer.toString(MAX_XML_TREE_CHARS));
        factory.setAttribute("jdk.xml.maxGeneralEntitySizeLimit",
                Integer.toString(MAX_XML_TREE_CHARS));
        factory.setAttribute("jdk.xml.maxParameterEntitySizeLimit", "1000000");

        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new ErrorHandler() {
            @Override public void warning(SAXParseException exception) {
                // Warnings do not make a well-formed tree unreadable.
            }

            @Override public void error(SAXParseException exception)
                    throws SAXException {
                throw exception;
            }

            @Override public void fatalError(SAXParseException exception)
                    throws SAXException {
                throw exception;
            }
        });
        var parsed = builder.parse(new InputSource(new StringReader(xml)));
        Element root = parsed.getDocumentElement();
        if (root == null) throw new SAXException("The document has no root element");
        return root;
    }

    static String xmlBody(String xml) {
        try {
            Element root = parseXmlRoot(xml);

            var state = new XmlRenderState();
            var tree = new StringBuilder(Math.min(xml.length() + 1024, 1_000_000));
            appendXmlElement(tree, root, 0, state, false);
            double tagColumnEm = Math.max(
                    XML_MIN_TAG_COLUMN_EM, state.maxTagWidthEm);
            double readerWidthEm = tagColumnEm + 1.2 + XML_MIN_VALUE_COLUMN_EM;

            var out = new StringBuilder(tree.length() + 1024);
            out.append("<section class=\"xml-reader\""
                    + " style=\"--xml-tag-min-width:")
                    .append(cssEm(tagColumnEm))
                    .append(";--xml-reader-min-width:")
                    .append(cssEm(readerWidthEm))
                    .append("\" aria-label=\"XML tree\">"
                    + "<div class=\"xml-heading\">"
                    + "<span>Node tag</span><span>Node value</span>"
                    + "<span class=\"xml-heading-actions\">"
                    + "<input class=\"xml-global-condense-toggle\""
                    + " id=\"xml-condense-all\" type=\"checkbox\" checked"
                    + " aria-label=\"Contract leaf nodes globally\">"
                    + "<label class=\"xml-global-condense-label\""
                    + " for=\"xml-condense-all\">"
                    + "<span class=\"xml-global-contract-text\">Contract leaves</span>"
                    + "<span class=\"xml-global-restore-text\">Restore leaves</span>"
                    + "</label>"
                    + "<input class=\"xml-pill-layout-toggle\""
                    + " id=\"xml-stack-pills\" type=\"checkbox\" checked"
                    + " aria-label=\"Use vertical key-value pills\">"
                    + "<label class=\"xml-pill-layout-label\""
                    + " for=\"xml-stack-pills\">"
                    + "<span class=\"xml-pill-stack-text\">Vertical pills</span>"
                    + "<span class=\"xml-pill-inline-text\">Horizontal pills</span>"
                    + "</label></span></div>"
                    + "<ul class=\"xml-tree\" role=\"tree\">")
                    .append(tree);
            return out.append("</ul></section>").toString();
        } catch (ParserConfigurationException | SAXException | IOException
                | IllegalArgumentException exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) message = "The XML could not be parsed.";
            return "<div class=\"xml-error\"><strong>XML tree unavailable.</strong> "
                    + escape(message) + "</div><pre class=\"plain\">"
                    + escape(fallbackText(xml)) + "</pre>";
        }
    }

    /** The source shown under an XML error, truncated to a bounded prefix. */
    public static String fallbackText(String text) {
        if (text.length() <= MAX_FALLBACK_CHARS) return text;
        int end = MAX_FALLBACK_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "\n… (truncated; "
                + (text.length() - end) + " more characters)";
    }

    private static void appendXmlElement(
            StringBuilder out, Element element, int depth,
            XmlRenderState state, boolean lastStructuralSibling) {
        if (state.nodes >= MAX_XML_TREE_NODES) {
            if (!state.nodeLimitShown) {
                out.append("<li class=\"xml-limit\" role=\"treeitem\">"
                        + "More nodes are not shown</li>");
                state.nodeLimitShown = true;
            }
            return;
        }
        state.nodes++;

        List<Element> children = new ArrayList<>();
        for (Node child = element.getFirstChild();
                child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) child);
            }
        }
        boolean depthLimited = !children.isEmpty() && depth >= MAX_XML_TREE_DEPTH;
        boolean expandable = element.hasAttributes() || !children.isEmpty();
        state.recordTag(depth, element.getTagName(), expandable);
        List<XmlMapEntry> compactEntries = new ArrayList<>();
        for (int index = 0;
                index < element.getAttributes().getLength(); index++) {
            Node attribute = element.getAttributes().item(index);
            state.addCompact(compactEntries, new XmlMapEntry(
                    attribute.getNodeName(), attribute.getNodeValue()));
        }
        int lastStructuralChild = -1;
        if (!depthLimited) {
            for (int index = 0; index < children.size(); index++) {
                Element child = children.get(index);
                if (isXmlExpandable(child)) {
                    lastStructuralChild = index;
                } else {
                    String value = directXmlValue(child);
                    state.addCompact(compactEntries, new XmlMapEntry(
                            child.getTagName(), value.isEmpty() ? "—" : value));
                }
            }
        }
        boolean hasNonCondensableRows = depthLimited || lastStructuralChild >= 0;
        String condenseId = "xml-condense-" + state.nodes;
        out.append("<li class=\"xml-node")
                .append(depth == 0 ? " xml-root" : "")
                .append(expandable
                        ? " xml-expandable"
                        : " xml-leaf xml-condensable")
                .append(lastStructuralSibling ? " xml-last-structural" : "")
                .append("\" style=\"--xml-indent:")
                .append(depth * XML_TREE_INDENT_EM)
                .append("em\" role=\"treeitem\">");
        out.append(expandable
                ? (hasNonCondensableRows
                        ? "<details open><summary class=\"xml-row"
                        : "<details><summary class=\"xml-row")
                : "<div class=\"xml-row")
                .append(depth > 0 ? " xml-nested-row" : "")
                .append("\">");
        appendXmlRow(out, element, compactEntries,
                compactEntries.isEmpty() ? null : condenseId);
        if (expandable) {
            out.append("</summary>");
            if (!compactEntries.isEmpty()) {
                out.append("<input class=\"xml-condense-toggle\" id=\"")
                        .append(condenseId)
                        .append("\" type=\"checkbox\""
                                + " aria-label=\"Toggle leaf contraction for this branch\">");
            }
            out.append("<ul class=\"xml-children")
                    .append(hasNonCondensableRows ? "" : " xml-all-condensable")
                    .append("\" role=\"group\">");
            for (int index = 0;
                    index < element.getAttributes().getLength(); index++) {
                appendXmlProperty(
                        out, element.getAttributes().item(index),
                        depth + 1, state);
            }
            if (depthLimited) {
                out.append("<li class=\"xml-limit\" role=\"treeitem\">"
                        + "Deeper nodes are not shown</li>");
            } else {
                for (int index = 0; index < children.size(); index++) {
                    appendXmlElement(out, children.get(index), depth + 1, state,
                            index == lastStructuralChild);
                }
            }
            out.append("</ul></details>");
        } else {
            out.append("</div>");
        }
        out.append("</li>");
    }

    private static void appendXmlRow(
            StringBuilder out, Element element,
            List<XmlMapEntry> compactEntries, String condenseId) {
        out.append("<span class=\"xml-tag-cell\">"
                + "<span class=\"xml-tag\">")
                .append(escape(element.getTagName()))
                .append("</span>");
        if (condenseId != null) {
            out.append("<label class=\"xml-condense-label\" for=\"")
                    .append(condenseId)
                    .append("\" title=\"Toggle leaf contraction for this branch\""
                            + " aria-hidden=\"true\"></label>");
        }
        out.append("</span><span class=\"xml-value\">"
                + "<span class=\"xml-normal-value\">");
        String value = directXmlValue(element);
        if (value.isEmpty()) {
            out.append("<span class=\"xml-empty\" aria-label=\"No direct value\">"
                    + "<span class=\"xml-empty-mark\">—</span></span>");
        } else {
            out.append(escape(value));
        }
        out.append("</span>");
        if (!compactEntries.isEmpty()) {
            String compactText = compactXmlMapText(compactEntries);
            out.append("<span class=\"xml-condensed-value\" title=\"")
                    .append(escape(compactText))
                    .append("\">");
            for (int index = 0; index < compactEntries.size(); index++) {
                XmlMapEntry entry = compactEntries.get(index);
                String pairId = condenseId + "-pair-" + index;
                out.append("<input class=\"xml-map-expand-toggle\" id=\"")
                        .append(pairId)
                        .append("\" type=\"checkbox\""
                                + " aria-label=\"Toggle full key and value\">"
                                + "<label class=\"xml-map-pair\" for=\"")
                        .append(pairId)
                        .append("\">"
                        + "<span class=\"xml-map-key\" title=\"")
                        .append(escape(entry.key()))
                        .append("\">")
                        .append(escape(entry.key()))
                        .append("</span><span class=\"xml-map-value\" title=\"")
                        .append(escape(entry.value()))
                        .append("\">")
                        .append(escape(entry.value()))
                        .append("</span></label>");
            }
            out.append("</span>");
        }
        out.append("</span>");
    }

    private static void appendXmlProperty(
            StringBuilder out, Node attribute, int depth,
            XmlRenderState state) {
        if (state.nodes >= MAX_XML_TREE_NODES) {
            if (!state.nodeLimitShown) {
                out.append("<li class=\"xml-limit\" role=\"treeitem\">"
                        + "More nodes are not shown</li>");
                state.nodeLimitShown = true;
            }
            return;
        }
        state.nodes++;
        state.recordTag(depth, attribute.getNodeName(), false);
        out.append("<li class=\"xml-node xml-property xml-condensable\""
                        + " style=\"--xml-indent:")
                .append(depth * XML_TREE_INDENT_EM)
                .append("em\" role=\"treeitem\">"
                        + "<div class=\"xml-row xml-nested-row\">"
                        + "<span class=\"xml-tag-cell xml-property-name\">")
                .append(escape(attribute.getNodeName()))
                .append("</span><span class=\"xml-value xml-property-value\">")
                .append(escape(attribute.getNodeValue()))
                .append("</span></div></li>");
    }

    static String directXmlValue(Element element) {
        var value = new StringBuilder();
        for (Node child = element.getFirstChild();
                child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE
                    || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                if (!value.isEmpty()) value.append(' ');
                value.append(child.getNodeValue());
            }
        }
        return value.toString().strip().replaceAll("\\s+", " ");
    }

    static boolean isXmlExpandable(Element element) {
        if (element.hasAttributes()) return true;
        for (Node child = element.getFirstChild();
                child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) return true;
        }
        return false;
    }

    static String compactXmlMapText(List<XmlMapEntry> entries) {
        var text = new StringBuilder();
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) text.append("; ");
            XmlMapEntry entry = entries.get(index);
            text.append(entry.key()).append(": ").append(entry.value());
        }
        return text.toString();
    }

    private static String cssEm(double value) {
        return String.format(Locale.ROOT, "%.2fem", value);
    }

    static String markdownBody(String markdown) {
        return markdownBody(markdown, HighlightEngines.AUTOMATIC);
    }

    static String markdownBody(
            String markdown, String highlightEnginePreference) {
        return markdownBody(markdown, highlightEnginePreference, true,
                new MarkdownRenderState());
    }

    private static String markdownBody(
            String markdown, String highlightEnginePreference,
            boolean anchorHeadings, MarkdownRenderState renderState) {
        StringBuilder out = new StringBuilder(markdown.length() + 256);
        String[] lines = markdown.split("\\R", -1);
        boolean inCode = false;
        HighlightSession codeHighlight = null;
        int codeLine = 1;
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            String leadingTrimmed = line.stripLeading();
            if (leadingTrimmed.startsWith("```")) {
                if (inCode) {
                    out.append("</code></pre>");
                    if (codeHighlight != null) codeHighlight.close();
                    codeHighlight = null;
                } else {
                    HighlightLanguage codeLanguage =
                            HighlightLanguage.forMarkdownFence(
                                    leadingTrimmed);
                    codeLine = 1;
                    int codeLineCount =
                            fencedCodeLineCount(lines, lineIndex + 1);
                    int codeLineDigits = Integer.toString(Math.max(
                            1, codeLineCount))
                            .length();
                    codeHighlight = HighlightEngines.open(
                            highlightEnginePreference,
                            fencedCodeDocument(
                                    lines, lineIndex + 1,
                                    codeLineCount,
                                    codeLanguage.kind()),
                            codeLanguage);
                    out.append("<pre class=\"source-code\" style=\"")
                            .append("--source-line-digits:")
                            .append(codeLineDigits)
                            .append("ch\"><code>");
                }
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                appendHighlightedCodeLine(
                        out, line, codeHighlight, codeLine++,
                        renderState.highlightBudget);
                continue;
            }
            TableHeader table = tableHeader(lines, lineIndex);
            if (table != null) {
                TableBody body = tableBody(lines, lineIndex, table.cells().size());
                List<List<String>> rows = body.rows();

                out.append("<table class=\"markdown-table\"><colgroup>");
                appendTableColumns(out, table.cells(), rows);
                out.append("</colgroup><thead><tr>");
                appendTableCells(out, "th", table.cells(), table.alignments());
                out.append("</tr></thead><tbody>");

                for (List<String> cells : rows) {
                    out.append("<tr>");
                    appendTableCells(out, "td", cells, table.alignments());
                    out.append("</tr>");
                }
                out.append("</tbody></table>");
                lineIndex = body.lastLine();
                continue;
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                int level = heading.group(1).length();
                out.append("<h").append(level);
                if (anchorHeadings) {
                    out.append(" id=\"")
                            .append(MarkdownOutline.anchor(
                                    renderState.nextHeading++))
                            .append('\"');
                }
                out.append('>')
                        .append(inline(heading.group(2)))
                        .append("</h").append(level).append('>');
                continue;
            }
            if (trimmed.matches("[-*_]{3,}")) {
                out.append("<hr>");
                continue;
            }
            if (trimmed.startsWith(">") && renderState.quoteDepth < MAX_QUOTE_DEPTH) {
                var quoted = new StringBuilder();
                int nextLine = lineIndex + 1;
                quoted.append(
                        trimmed.substring(1).stripLeading());
                while (nextLine < lines.length
                        && lines[nextLine].strip().startsWith(">")) {
                    String continuation =
                            lines[nextLine].strip()
                                    .substring(1).stripLeading();
                    quoted.append('\n').append(continuation);
                    nextLine++;
                }
                renderState.quoteDepth++;
                out.append("<blockquote>")
                        .append(markdownBody(quoted.toString(),
                                highlightEnginePreference, false,
                                renderState))
                        .append("</blockquote>");
                renderState.quoteDepth--;
                lineIndex = nextLine - 1;
                continue;
            }
            if (listMarker(line) != null) {
                ListBlock list = parseListBlock(lines, lineIndex);
                appendList(out, list.nodes());
                lineIndex = list.lastLine();
            } else {
                InlineBlock paragraph = collectInlineBlock(lines, lineIndex, trimmed);
                out.append("<p>").append(paragraph.html()).append("</p>");
                lineIndex = paragraph.lastLine();
            }
        }
        if (inCode) {
            out.append("</code></pre>");
            if (codeHighlight != null) codeHighlight.close();
        }
        return out.toString();
    }

    private static final class MarkdownRenderState {
        private int nextHeading;
        private int quoteDepth;
        private final HighlightBudget highlightBudget = HighlightBudget.perDocument();
    }

    /**
     * Parses one contiguous Markdown list into a small indentation-aware tree.
     * The Reader needs the tree, rather than a flat sequence of {@code li}
     * elements, both for ordinary nested lists and to know whether a task is
     * the last child when drawing its connector.
     */
    static ListBlock parseListBlock(
            String[] lines, int firstLine) {
        ListMarker first = listMarker(lines[firstLine]);
        if (first == null) {
            throw new IllegalArgumentException("not a list line");
        }
        var roots = new ArrayList<ListNode>();
        var ancestors = new ArrayDeque<ListNode>();
        ListNode current = null;
        int lastLine = firstLine - 1;
        for (int lineIndex = firstLine;
                lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            if (line.isBlank()) {
                int nextLine = lineIndex + 1;
                while (nextLine < lines.length
                        && lines[nextLine].isBlank()) {
                    nextLine++;
                }
                ListMarker nextMarker = nextLine < lines.length
                        ? listMarker(lines[nextLine]) : null;
                if (nextMarker != null
                        && nextMarker.indent() >= first.indent()) {
                    // Skip the whole blank run so each blank is scanned once.
                    lastLine = nextLine - 1;
                    lineIndex = nextLine - 1;
                    continue;
                }
                break;
            }

            ListMarker marker = listMarker(line);
            if (marker != null) {
                if (marker.indent() < first.indent()) break;
                while (!ancestors.isEmpty()
                        && (ancestors.peek().indent() >= marker.indent()
                                || ancestors.size() >= MAX_LIST_DEPTH)) {
                    ancestors.pop();
                }
                var node = new ListNode(
                        marker.indent(), marker.ordered(),
                        marker.marker(), marker.body());
                if (ancestors.isEmpty()) {
                    roots.add(node);
                } else {
                    ancestors.peek().children().add(node);
                }
                ancestors.push(node);
                current = node;
                lastLine = lineIndex;
                continue;
            }

            if (current == null
                    || startsNonListBlock(lines, lineIndex)) {
                break;
            }
            current.appendContinuation(line.strip());
            lastLine = lineIndex;
        }
        return new ListBlock(List.copyOf(roots), lastLine);
    }

    private static boolean startsNonListBlock(
            String[] lines, int lineIndex) {
        String trimmed = lines[lineIndex].strip();
        return trimmed.startsWith("```")
                || trimmed.startsWith(">")
                || HEADING.matcher(trimmed).matches()
                || trimmed.matches("[-*_]{3,}")
                || tableHeader(lines, lineIndex) != null;
    }

    static ListMarker listMarker(String line) {
        Matcher matcher = LIST_ITEM.matcher(line);
        if (!matcher.matches()) return null;
        String leading = matcher.group(1);
        return new ListMarker(
                indentationWidth(leading),
                matcher.group(3) != null,
                matcher.group(2),
                matcher.group(4));
    }

    private static int indentationWidth(String whitespace) {
        int width = 0;
        for (int index = 0; index < whitespace.length(); index++) {
            if (whitespace.charAt(index) == '\t') {
                width += 4 - width % 4;
            } else {
                width++;
            }
        }
        return width;
    }

    private static void appendList(
            StringBuilder out, List<ListNode> nodes) {
        boolean taskTree = nodes.stream().anyMatch(ListNode::containsTask);
        appendListGroups(
                out, nodes, taskTree, List.of(), 0);
    }

    /** Emits consecutive ordered/unordered runs using their semantic element. */
    private static void appendListGroups(
            StringBuilder out, List<ListNode> nodes,
            boolean taskTree, List<Boolean> ancestorLast,
            int depth) {
        int start = 0;
        while (start < nodes.size()) {
            boolean ordered = nodes.get(start).ordered();
            int end = start + 1;
            while (end < nodes.size()
                    && nodes.get(end).ordered() == ordered) {
                end++;
            }
            String tag = ordered ? "ol" : "ul";
            out.append('<').append(tag);
            if (taskTree) out.append(" class=\"task-tree\"");
            out.append('>');
            for (int index = start; index < end; index++) {
                ListNode node = nodes.get(index);
                boolean last = index == nodes.size() - 1;
                appendListNode(
                        out, node, taskTree,
                        ancestorLast, depth, last);
            }
            out.append("</").append(tag).append('>');
            start = end;
        }
    }

    private static void appendListNode(
            StringBuilder out, ListNode node,
            boolean taskTree, List<Boolean> ancestorLast,
            int depth, boolean last) {
        if (!taskTree) {
            out.append("<li>")
                    .append(inline(node.text()));
            if (!node.children().isEmpty()) {
                appendListGroups(
                        out, node.children(), false,
                        List.of(), depth + 1);
            }
            out.append("</li>");
            return;
        }

        Task task = task(node.text());
        String prefix;
        if (task != null) {
            prefix = (node.ordered() ? node.marker() + " " : "")
                    + (task.checked() ? "\u2611\uFE0E" : "\u2610\uFE0E");
        } else {
            prefix = node.ordered() ? node.marker() : "\u2022";
        }
        out.append("<li class=\"task-tree-item\"><div class=\"task-row")
                .append(task == null ? "" : " task-row-checkbox")
                .append(task != null && task.checked()
                        ? " task-row-complete" : "")
                .append("\" data-connector=\"")
                .append(escape(connector(ancestorLast, depth, last)))
                .append("\"><span class=\"task-prefix\"");
        if (task != null) {
            out.append(" role=\"checkbox\" aria-checked=\"")
                    .append(task.checked())
                    .append("\" aria-readonly=\"true\" aria-label=\"")
                    .append(task.checked()
                            ? "Completed task" : "Unchecked task")
                    .append('"');
        } else {
            out.append(" aria-hidden=\"true\"");
        }
        out.append(" data-prefix=\"")
                .append(escape(prefix))
                .append("\"></span><span class=\"task-label\">")
                .append(inline(task == null
                        ? node.text() : task.text()))
                .append("</span></div>");
        if (!node.children().isEmpty()) {
            var childAncestors =
                    new ArrayList<Boolean>(ancestorLast);
            if (depth > 0) childAncestors.add(last);
            appendListGroups(
                    out, node.children(), true,
                    List.copyOf(childAncestors), depth + 1);
        }
        out.append("</li>");
    }

    static String connector(
            List<Boolean> ancestorLast, int depth,
            boolean last) {
        if (depth == 0) return "";
        StringBuilder connector = new StringBuilder(depth * 3);
        for (boolean ancestorWasLast : ancestorLast) {
            connector.append(
                    ancestorWasLast ? "   " : "\u2502  ");
        }
        connector.append(last ? "\u2514\u2500 " : "\u251C\u2500 ");
        return connector.toString();
    }

    static Task task(String text) {
        Matcher unchecked = UNCHECKED_TASK.matcher(text);
        if (unchecked.matches()) {
            return new Task(false,
                    unchecked.group(1) == null
                            ? "" : unchecked.group(1));
        }
        Matcher checked = CHECKED_TASK.matcher(text);
        if (checked.matches()) {
            return new Task(true,
                    checked.group(1) == null
                            ? "" : checked.group(1));
        }
        return null;
    }

    /** Counts physical code lines up to the next fence for a shared gutter. */
    static int fencedCodeLineCount(String[] lines, int firstCodeLine) {
        int count = 0;
        for (int index = firstCodeLine; index < lines.length; index++) {
            if (lines[index].stripLeading().startsWith("```")) break;
            count++;
        }
        return count;
    }

    static DocumentContent fencedCodeDocument(
            String[] lines, int firstCodeLine,
            int lineCount, DocumentKind kind) {
        var text = new StringBuilder();
        int[] starts = new int[Math.max(1, lineCount)];
        for (int offset = 0; offset < lineCount; offset++) {
            if (offset > 0) text.append('\n');
            starts[offset] = text.length();
            text.append(lines[firstCodeLine + offset]);
        }
        return new DocumentContent(
                Path.of("reader-code-block"), kind, text.toString(),
                StandardCharsets.UTF_8, text.length(), 0, starts);
    }

    /**
     * Emits one numbered code row using the same tokenizer and token style
     * names as the native views and {@link HighlightTheme}.
     */
    private static void appendHighlightedCodeLine(
            StringBuilder out, String line,
            HighlightSession highlights, int lineNumber,
            HighlightBudget budget) {
        out.append("<span class=\"source-line\"><span class=\"source-line-number\"")
                .append(" aria-hidden=\"true\">")
                .append(lineNumber)
                .append("</span><span class=\"source-line-text\">");
        // An empty line stays an empty element; its height comes from CSS
        // generated content, which selection and clipboard copies never
        // include. A literal zero-width space here would leak into copied
        // text as a stray U+200B on every blank code line.
        if (!line.isEmpty()) {
            List<HighlightToken> tokens =
                    budget.tokens(highlights, lineNumber - 1, line);
            for (HighlightToken token : tokens) {
                out.append("<span class=\"syntax-")
                        .append(token.role().styleClass())
                        .append("\">")
                        .append(escape(line.substring(
                                token.start(), token.end())))
                        .append("</span>");
            }
        }
        out.append("</span></span>");
    }

    /**
     * Collects Markdown soft-wrapped source lines into one inline block. A
     * physical newline without a blank line is a space in Markdown, including
     * a lazy/indented continuation below a list marker. Structural constructs
     * still begin a new block.
     */
    static InlineBlock collectInlineBlock(String[] lines, int lineIndex,
            String firstLine) {
        StringBuilder html = new StringBuilder();
        appendSoftLine(html, firstLine, true);
        int nextLine = lineIndex + 1;
        while (nextLine < lines.length && !startsMarkdownBlock(lines, nextLine)) {
            appendSoftLine(html, lines[nextLine].strip(), false);
            nextLine++;
        }
        return new InlineBlock(html.toString(), nextLine - 1);
    }

    private static void appendSoftLine(StringBuilder html, String line, boolean first) {
        if (!first) html.append(' ');
        html.append(inline(line.strip()));
    }

    static boolean startsMarkdownBlock(String[] lines, int lineIndex) {
        String trimmed = lines[lineIndex].strip();
        if (trimmed.isEmpty()
                || trimmed.startsWith("```")
                || trimmed.startsWith(">")
                || HEADING.matcher(trimmed).matches()
                || BULLET.matcher(trimmed).matches()
                || NUMBERED.matcher(trimmed).matches()
                || trimmed.matches("[-*_]{3,}")) {
            return true;
        }
        return tableHeader(lines, lineIndex) != null;
    }

    static TableHeader tableHeader(String[] lines, int lineIndex) {
        if (lineIndex + 1 >= lines.length || !hasTablePipe(lines[lineIndex])) {
            return null;
        }
        List<String> cells = tableCells(lines[lineIndex]);
        List<String> delimiters = tableCells(lines[lineIndex + 1]);
        if (cells.isEmpty() || cells.size() != delimiters.size()
                || cells.size() > MAX_TABLE_COLUMNS) {
            return null;
        }
        List<TableAlignment> alignments = new ArrayList<>(delimiters.size());
        for (String delimiter : delimiters) {
            String value = delimiter.strip();
            if (!TABLE_DELIMITER.matcher(value).matches()) {
                return null;
            }
            alignments.add(value.startsWith(":")
                    ? (value.endsWith(":") ? TableAlignment.CENTER : TableAlignment.LEFT)
                    : (value.endsWith(":") ? TableAlignment.RIGHT : TableAlignment.NONE));
        }
        return new TableHeader(cells, alignments);
    }

    /**
     * Collects the body rows below the table header at {@code headerLine},
     * padded to {@code columns}. The empty cells added to short rows may not
     * outnumber the characters of the table's source so far; a row that
     * would exceed that ends the table, so a wide header over many
     * near-empty rows stays linear instead of growing as rows x columns.
     */
    static TableBody tableBody(String[] lines, int headerLine, int columns) {
        List<List<String>> rows = new ArrayList<>();
        long source = lines[headerLine].length() + lines[headerLine + 1].length() + 2L;
        long padding = 0;
        int rowIndex = headerLine + 2;
        while (rowIndex < lines.length && isTableBodyRow(lines[rowIndex])) {
            List<String> cells = tableCells(lines[rowIndex]);
            int missing = Math.max(0, columns - cells.size());
            source += lines[rowIndex].length() + 1;
            if (padding + missing > source) break;
            padding += missing;
            rows.add(normalizeCells(cells, columns));
            rowIndex++;
        }
        return new TableBody(rows, rowIndex - 1);
    }

    static boolean isTableBodyRow(String line) {
        return !line.isBlank() && hasTablePipe(line);
    }

    private static boolean hasTablePipe(String line) {
        boolean inCode = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                i++;
            } else if (c == '`') {
                inCode = !inCode;
            } else if (c == '|' && !inCode) {
                return true;
            }
        }
        return false;
    }

    static List<String> tableCells(String line) {
        String value = line.strip();
        int start = value.startsWith("|") ? 1 : 0;
        int end = hasTrailingTablePipe(value) ? value.length() - 1 : value.length();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inCode = false;
        for (int i = start; i < end; i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < end && value.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (c == '`') {
                inCode = !inCode;
                cell.append(c);
            } else if (c == '|' && !inCode) {
                cells.add(cell.toString().strip());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().strip());
        return cells;
    }

    private static boolean hasTrailingTablePipe(String line) {
        if (!line.endsWith("|")) {
            return false;
        }
        int backslashes = 0;
        for (int i = line.length() - 2; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 0;
    }

    static List<String> normalizeCells(List<String> cells, int count) {
        List<String> normalized = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            normalized.add(i < cells.size() ? cells.get(i) : "");
        }
        return normalized;
    }

    private static void appendTableCells(StringBuilder out, String tag, List<String> cells,
            List<TableAlignment> alignments) {
        for (int i = 0; i < cells.size(); i++) {
            TableAlignment alignment = alignments.get(i);
            out.append('<').append(tag);
            if (alignment != TableAlignment.NONE) {
                out.append(" class=\"align-").append(alignment.cssName).append('"');
            }
            out.append('>').append(inline(cells.get(i))).append("</").append(tag).append('>');
        }
    }

    /**
     * Gives every Markdown column a content-informed share of the available
     * width, then bounds that share so a long code token or prose cell cannot
     * squeeze its neighbours down to a few characters. The browser's native
     * auto-table algorithm heavily favours unbreakable inline-code fragments;
     * an explicit colgroup lets fixed layout remain responsive and predictable.
     */
    private static void appendTableColumns(StringBuilder out, List<String> header,
            List<List<String>> rows) {
        int columnCount = header.size();
        double[] weights = new double[columnCount];
        for (int column = 0; column < columnCount; column++) {
            weights[column] = tableCellWidth(header.get(column));
            for (List<String> row : rows) {
                weights[column] = Math.max(
                        weights[column], tableCellWidth(row.get(column)));
            }
        }

        double[] widths = balancedColumnWidths(weights);
        for (double width : widths) {
            out.append("<col style=\"width:")
                    .append(String.format(Locale.ROOT, "%.2f", width))
                    .append("%\">");
        }
    }

    static double tableCellWidth(String markdown) {
        String visible = LINK.matcher(markdown).replaceAll("$1");
        visible = visible.replace("`", "")
                .replace("**", "")
                .replace("__", "");
        int characters = visible.codePointCount(0, visible.length());
        // Past this point additional prose should wrap rather than continuing
        // to buy width from the other columns.
        return Math.max(1, Math.min(48, characters));
    }

    static double[] balancedColumnWidths(double[] weights) {
        int columnCount = weights.length;
        if (columnCount == 1) return new double[]{100};

        double minimum = Math.min(12.5, 50.0 / columnCount);
        double maximum = Math.min(70.0, 160.0 / columnCount);
        double low = 0;
        double high = 100;
        for (int iteration = 0; iteration < 80; iteration++) {
            double scale = (low + high) / 2;
            double total = 0;
            for (double weight : weights) {
                total += Math.max(minimum, Math.min(maximum, scale * weight));
            }
            if (total < 100) low = scale;
            else high = scale;
        }

        double[] widths = new double[columnCount];
        double total = 0;
        for (int column = 0; column < columnCount; column++) {
            widths[column] = Math.max(
                    minimum, Math.min(maximum, high * weights[column]));
            total += widths[column];
        }
        // Absorb floating-point residue without allowing the printed values to
        // leave a visible sliver at the right edge of a full-width table.
        widths[columnCount - 1] += 100 - total;
        return widths;
    }

    static String htmlBody(String html) {
        String sanitized = stripSuppressedRawTextElements(html);
        var out = new StringBuilder(sanitized.length());
        var input = new StoppableReader(sanitized);
        try {
            new ParserDelegator().parse(input,
                    new HTMLEditorKit.ParserCallback() {
                        /** The parser's open elements, implied ones included. */
                        private int depth;
                        private int suppressedDepth;
                        private boolean previousEndedSpan;

                        @Override
                        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                            if (++depth > MAX_HTML_DEPTH) input.stop();
                            if (input.stopped()) return;
                            if (SUPPRESSED.contains(tag)) {
                                suppressedDepth++;
                                return;
                            }
                            if (suppressedDepth > 0 || !ALLOWED.contains(tag)) return;
                            if (tag == HTML.Tag.SPAN && previousEndedSpan) {
                                out.append(' ');
                            }
                            previousEndedSpan = false;
                            if (tag == HTML.Tag.A) {
                                out.append("<span class=\"link\"");
                                Object target = attrs.getAttribute(HTML.Attribute.HREF);
                                if (target != null) {
                                    out.append(" data-link-location=\"")
                                            .append(escape(target.toString()))
                                            .append('"');
                                }
                                out.append('>');
                            } else {
                                out.append('<')
                                        .append(tag.toString().toLowerCase(Locale.ROOT))
                                        .append('>');
                            }
                        }

                        @Override
                        public void handleEndTag(HTML.Tag tag, int pos) {
                            depth--;
                            if (input.stopped()) return;
                            if (SUPPRESSED.contains(tag)) {
                                if (suppressedDepth > 0) suppressedDepth--;
                                return;
                            }
                            if (suppressedDepth > 0 || !ALLOWED.contains(tag)) return;
                            if (tag == HTML.Tag.A) {
                                out.append("</span>");
                            } else {
                                out.append("</")
                                        .append(tag.toString().toLowerCase(Locale.ROOT))
                                        .append('>');
                            }
                            previousEndedSpan = tag == HTML.Tag.SPAN;
                        }

                        @Override
                        public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                            if (input.stopped()) return;
                            if (suppressedDepth == 0 && ALLOWED.contains(tag)) {
                                out.append('<').append(tag.toString().toLowerCase(Locale.ROOT))
                                        .append('>');
                                previousEndedSpan = false;
                            }
                        }

                        @Override
                        public void handleText(char[] data, int pos) {
                            if (suppressedDepth == 0 && !input.stopped()) {
                                out.append(escape(new String(data)));
                                previousEndedSpan = false;
                            }
                        }

                        @Override
                        public void handleError(String errorMsg, int pos) {
                            // Parser recovery is intentional; malformed HTML is
                            // rendered as safely as the successfully parsed parts.
                        }
                    }, true);
        } catch (IOException impossibleForStringReader) {
            return "<pre class=\"plain\">" + escape(html) + "</pre>";
        }
        if (input.stopped()) {
            return "<pre class=\"plain\">" + escape(html) + "</pre>";
        }
        return out.toString();
    }

    /**
     * Source for Swing's HTML parser that can end the input early. The parser
     * reads ahead at most a small buffer, so stopping bounds the rest of the
     * parse without throwing through its error recovery.
     */
    static final class StoppableReader extends StringReader {
        private boolean stopped;

        StoppableReader(String text) {
            super(text);
        }

        void stop() {
            stopped = true;
        }

        boolean stopped() {
            return stopped;
        }

        @Override
        public int read() throws IOException {
            return stopped ? -1 : super.read();
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            return stopped ? -1 : super.read(buffer, offset, length);
        }
    }

    /**
     * Removes raw-text metadata before handing HTML to Swing's HTML 3.2 parser.
     * That parser reports the contents of {@code style}, {@code script}, and
     * {@code title} as normal text when those elements are misplaced in the
     * body, without reporting the surrounding tags. A small quote-aware scan
     * closes that recovery hole while leaving ordinary tags and attributes for
     * the structural whitelist to process.
     */
    static String stripSuppressedRawTextElements(String html) {
        StringBuilder out = null;
        int copiedThrough = 0;
        int scan = 0;
        while (scan < html.length()) {
            int tagStart = html.indexOf('<', scan);
            if (tagStart < 0) break;

            if (html.regionMatches(tagStart, "<!--", 0, 4)) {
                int commentEnd = html.indexOf("-->", tagStart + 4);
                scan = commentEnd < 0 ? html.length() : commentEnd + 3;
                continue;
            }

            int tagEnd = findTagEnd(html, tagStart + 1);
            if (tagEnd < 0) break;
            String rawTextTag = suppressedRawTextStartTag(html, tagStart, tagEnd);
            if (rawTextTag == null) {
                scan = tagEnd + 1;
                continue;
            }

            int elementEnd = findRawTextElementEnd(
                    html, tagEnd + 1, rawTextTag);
            if (out == null) out = new StringBuilder(html.length());
            out.append(html, copiedThrough, tagStart);
            copiedThrough = elementEnd;
            scan = elementEnd;
        }
        if (out == null) return html;
        return out.append(html, copiedThrough, html.length()).toString();
    }

    private static String suppressedRawTextStartTag(
            String html, int tagStart, int tagEnd) {
        int nameStart = tagStart + 1;
        if (nameStart >= tagEnd
                || html.charAt(nameStart) == '/'
                || html.charAt(nameStart) == '!'
                || html.charAt(nameStart) == '?') {
            return null;
        }
        for (String name : RAW_TEXT_SUPPRESSED) {
            int nameEnd = nameStart + name.length();
            if (nameEnd <= tagEnd
                    && html.regionMatches(true, nameStart, name, 0, name.length())
                    && isTagNameBoundary(html.charAt(nameEnd))) {
                return name;
            }
        }
        return null;
    }

    private static int findRawTextElementEnd(
            String html, int contentStart, String tagName) {
        int scan = contentStart;
        while (scan < html.length()) {
            int closeStart = html.indexOf('<', scan);
            if (closeStart < 0) return html.length();
            int nameStart = closeStart + 2;
            int nameEnd = nameStart + tagName.length();
            if (closeStart + 1 < html.length()
                    && html.charAt(closeStart + 1) == '/'
                    && nameEnd < html.length()
                    && html.regionMatches(
                            true, nameStart, tagName, 0, tagName.length())
                    && isTagNameBoundary(html.charAt(nameEnd))) {
                int closeEnd = findTagEnd(html, nameEnd);
                return closeEnd < 0 ? html.length() : closeEnd + 1;
            }
            scan = closeStart + 1;
        }
        return html.length();
    }

    private static int findTagEnd(String html, int start) {
        char quote = 0;
        for (int index = start; index < html.length(); index++) {
            char value = html.charAt(index);
            if (quote != 0) {
                if (value == quote) quote = 0;
            } else if (value == '\'' || value == '"') {
                quote = value;
            } else if (value == '>') {
                return index;
            }
        }
        return -1;
    }

    private static boolean isTagNameBoundary(char value) {
        return Character.isWhitespace(value) || value == '/' || value == '>';
    }

    /**
     * Works around two JavaFX WebView limitations without changing the text
     * exposed by the resulting DOM. Passing UTF-16 surrogate pairs through
     * {@code WebEngine.loadContent} corrupts them, and WebView's macOS text
     * renderer drops all but the first supplementary glyph in one text run.
     * Numeric references avoid the first problem; one inline span per Unicode
     * grapheme avoids the second while keeping joined emoji sequences intact.
     */
    static String webViewSafeBody(String body) {
        if (body.codePoints().noneMatch(
                codePoint -> codePoint > Character.MAX_VALUE)) {
            return body;
        }
        var out = new StringBuilder(body.length() + 32);
        int offset = 0;
        while (offset < body.length()) {
            int tagStart = body.indexOf('<', offset);
            int textEnd = tagStart < 0 ? body.length() : tagStart;
            appendWebViewSafeText(out, body.substring(offset, textEnd));
            if (tagStart < 0) break;

            int tagEnd = body.indexOf('>', tagStart + 1);
            if (tagEnd < 0) {
                appendSupplementaryReferences(out, body.substring(tagStart));
                break;
            }
            appendSupplementaryReferences(
                    out, body.substring(tagStart, tagEnd + 1));
            offset = tagEnd + 1;
        }
        return out.toString();
    }

    private static void appendWebViewSafeText(
            StringBuilder out, String text) {
        Matcher graphemes = GRAPHEME.matcher(text);
        while (graphemes.find()) {
            String grapheme = graphemes.group();
            if (grapheme.codePoints().anyMatch(
                    codePoint -> codePoint > Character.MAX_VALUE)) {
                out.append("<span class=\"unicode-glyph\">");
                grapheme.codePoints().forEach(codePoint -> out.append("&#x")
                        .append(Integer.toHexString(codePoint))
                        .append(';'));
                out.append("</span>");
            } else {
                out.append(grapheme);
            }
        }
    }

    private static void appendSupplementaryReferences(
            StringBuilder out, String value) {
        value.codePoints().forEach(codePoint -> {
            if (codePoint > Character.MAX_VALUE) {
                out.append("&#x")
                        .append(Integer.toHexString(codePoint))
                        .append(';');
            } else {
                out.append((char) codePoint);
            }
        });
    }

    static String inline(String markdown) {
        // Code spans are protected first so identifiers such as `_literal_`
        // are not reinterpreted, then links, so emphasis never reaches a link
        // target. Protected spans become fixed private-use markers (any such
        // characters in the input are replaced first) and are restored in one
        // linear pass.
        String source = withoutMarkers(markdown);
        var code = new ArrayList<String>();
        var text = new StringBuilder(source.length());
        Matcher codes = INLINE_CODE.matcher(source);
        int copied = 0;
        while (codes.find()) {
            text.append(source, copied, codes.start());
            mark(text, CODE_MARK, code.size());
            code.add(codes.group(1));
            copied = codes.end();
        }
        text.append(source, copied, source.length());

        String value = escape(text.toString());
        var links = new ArrayList<String[]>();
        var linked = new StringBuilder(value.length());
        Matcher link = LINK.matcher(value);
        copied = 0;
        while (link.find()) {
            linked.append(value, copied, link.start());
            mark(linked, LINK_MARK, links.size());
            links.add(new String[]{link.group(1), link.group(2)});
            copied = link.end();
        }
        linked.append(value, copied, value.length());
        return restoreMarkers(emphasize(linked.toString()), code, links);
    }

    private static String emphasize(String value) {
        value = replace(STRONG, value, "<strong>$1$2</strong>");
        return replace(EMPHASIS, value, "<em>$1$2</em>");
    }

    private static void mark(StringBuilder out, char marker, int index) {
        out.append(marker).append(index).append(MARK_END);
    }

    private static String withoutMarkers(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == CODE_MARK || c == LINK_MARK || c == MARK_END) {
                return value.replace(CODE_MARK, '\uFFFD').replace(LINK_MARK, '\uFFFD')
                        .replace(MARK_END, '\uFFFD');
            }
        }
        return value;
    }

    /**
     * Expands markers in one pass. A link target is kept only as inert,
     * escaped metadata for copying (code spans in it as plain text); there is
     * deliberately no href, so content cannot navigate, fetch or execute.
     */
    private static String restoreMarkers(String value, List<String> code,
                                         List<String[]> links) {
        var out = new StringBuilder(value.length() + 32);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != CODE_MARK && c != LINK_MARK) {
                out.append(c);
                continue;
            }
            int end = value.indexOf(MARK_END, i + 1);
            int index = Integer.parseInt(value, i + 1, end, 10);
            if (c == CODE_MARK) {
                out.append("<code>").append(escape(code.get(index))).append("</code>");
            } else {
                String[] parts = links.get(index);
                out.append("<span class=\"link\" data-link-location=\"")
                        .append(plainCode(parts[1], code))
                        .append("\">")
                        .append(restoreMarkers(emphasize(parts[0]), code, links))
                        .append("</span>");
            }
            i = end;
        }
        return out.toString();
    }

    /** Replaces code markers in an (escaped) link target with their escaped text. */
    private static String plainCode(String target, List<String> code) {
        if (target.indexOf(CODE_MARK) < 0) return target;
        var out = new StringBuilder(target.length() + 16);
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c != CODE_MARK) {
                out.append(c);
                continue;
            }
            int end = target.indexOf(MARK_END, i + 1);
            out.append(escape(code.get(Integer.parseInt(target, i + 1, end, 10))));
            i = end;
        }
        return out.toString();
    }

    private static String replace(Pattern pattern, String input, String replacement) {
        return pattern.matcher(input).replaceAll(replacement);
    }

    private static String wrap(String body, ReaderStyle style, boolean xmlLayout) {
        String readerFamily = cssString(style.readerFontFamily());
        String codeFamily = cssString(style.codeFontFamily());
        String weight = style.bold() ? "700" : "400";
        String fontStyle = style.italic() ? "italic" : "normal";
        String syntaxCss = syntaxCss();
        String bodyClass = xmlLayout ? " class=\"xml-reader-page\"" : "";
        // Locale.ROOT: on comma-decimal locales "%.2f" would emit invalid CSS.
        return String.format(Locale.ROOT, """
                <!doctype html><html><head>
                <meta charset="UTF-8">
                <meta http-equiv="Content-Security-Policy"
                      content="default-src 'none'; img-src 'none'; media-src 'none'; \
                object-src 'none'; frame-src 'none'; connect-src 'none'; style-src 'unsafe-inline'; \
                base-uri 'none'; form-action 'none'">
                <style>
                :root { color-scheme: dark; }
                html, body { margin:0; min-height:100%%; background:#111; color:#ddd; }
                body { box-sizing:border-box; padding:2.3em max(2em, calc((100%% - 78em)/2));
                  font-family:'%s', "Iowan Old Style", Charter, "Palatino Linotype",
                    Palatino, Constantia, Georgia, serif;
                  font-size:%.2fpx; font-weight:%s;
                  font-style:%s; line-height:%.3f; letter-spacing:%.3fpx;
                  overflow-wrap:%s; }
                body.xml-reader-page { padding-left:2em; padding-right:2em; }
                p, li, blockquote { max-width:78em; }
                em { font-weight:700; }
                h1,h2,h3,h4,h5,h6 { color:#f2f2f2; line-height:1.2;
                  border-bottom:1px solid #383838; padding-bottom:.28em; }
                pre, code { font-family:'%s', ui-monospace, "SFMono-Regular",
                  Menlo, Consolas, monospace; background:#1b1b1b; }
                pre { padding:1em; overflow-x:hidden; white-space:pre-wrap;
                  overflow-wrap:anywhere; word-break:break-word; tab-size:%d; }
                code { padding:.08em .28em; border-radius:.2em; }
                pre.source-code { padding:.75em 0; }
                pre.source-code > code { display:block; padding:0; background:transparent; }
                .source-line { display:grid; grid-template-columns:max-content minmax(0,1fr); }
                .source-line-number { min-width:var(--source-line-digits, 1ch);
                  color:#707070; text-align:right; padding:0 .8em;
                  margin-right:.8em; border-right:1px solid #383838;
                  user-select:none; -webkit-user-select:none; }
                .source-line-text { min-width:0; white-space:pre-wrap;
                  overflow-wrap:anywhere; word-break:break-word; }
                .source-line-text:empty::before { content:"\\200b"; }
                blockquote { border-left:.25em solid #666; margin-left:0; padding-left:1em; color:#bbb; }
                table { border-collapse:collapse; }
                table.markdown-table { width:100%%; max-width:78em; table-layout:fixed; }
                th,td { border:1px solid #555; padding:.35em .6em; }
                .align-left { text-align:left; } .align-center { text-align:center; }
                .align-right { text-align:right; }
                .link { color:#7db9ff; text-decoration:underline; cursor:pointer; }
                ul.task-tree, ol.task-tree { list-style:none; margin:.45em 0; padding:0; }
                .task-tree .task-tree { margin:0; }
                .task-tree-item { list-style:none; margin:0; padding:0; max-width:78em; }
                .task-row { display:grid;
                  grid-template-columns:max-content max-content minmax(0,1fr);
                  column-gap:.24em; align-items:stretch; }
                .task-row::before { content:attr(data-connector);
                  color:#777; opacity:.50; white-space:pre; font-size:.92em;
                  transform:translateX(.9em) scaleY(1.18);
                  transform-origin:center;
                  font-family:'%s', ui-monospace, "SFMono-Regular",
                    Menlo, Consolas, monospace;
                  user-select:none; -webkit-user-select:none; }
                .task-prefix { position:relative; min-width:1.2em;
                  font-size:1.25em; line-height:1;
                  text-align:right; white-space:pre;
                  font-family:"Apple Symbols", "Segoe UI Symbol",
                    "Noto Sans Symbols 2", sans-serif;
                  user-select:none; -webkit-user-select:none; }
                .task-prefix::before { content:attr(data-prefix);
                  position:relative; top:.12em; }
                .task-row-checkbox .task-prefix::after { content:"";
                  position:absolute; left:50%%; top:1.08em; bottom:.08em;
                  border-left:1px solid #464646; pointer-events:none; }
                .task-label { min-width:0; overflow-wrap:inherit; }
                .task-row-complete .task-label {
                  text-decoration:line-through; opacity:.50; }
                .xml-reader { width:100%%;
                  min-width:var(--xml-reader-min-width, 45.2em);
                  font-family:'%s', ui-monospace, "SFMono-Regular",
                    Menlo, Consolas, monospace; font-style:normal;
                  --xml-row-midline:%.3fem; --xml-row-height:%.3fem; }
                .xml-heading, .xml-row { display:grid;
                  grid-template-columns:
                    minmax(var(--xml-tag-min-width, 16em), .5fr)
                    minmax(0, 1.5fr);
                  column-gap:1.2em; }
                .xml-heading { position:sticky; top:0; z-index:20;
                  margin-bottom:.55em; padding:.45em .65em;
                  border-bottom:1px solid #444; background:#111;
                  -webkit-transform:translate3d(0, 0, 0);
                  transform:translate3d(0, 0, 0);
                  -webkit-backface-visibility:hidden;
                  backface-visibility:hidden; will-change:transform;
                  isolation:isolate;
                  color:#858585; font-size:.78em; font-weight:700;
                  letter-spacing:.08em; text-transform:uppercase; }
                .xml-heading > :first-child { padding-left:1em; }
                .xml-heading-actions { position:absolute; right:.65em; top:50%%;
                  transform:translateY(-50%%); display:flex; align-items:center;
                  gap:.45em; }
                .xml-global-condense-toggle, .xml-pill-layout-toggle {
                  position:absolute; width:1px;
                  height:1px; overflow:hidden; clip-path:inset(50%%);
                  white-space:nowrap; }
                .xml-global-condense-label, .xml-pill-layout-label {
                  box-sizing:border-box;
                  border:1px solid #555; border-radius:.25em;
                  padding:.28em .65em; background:#1b1b1b; color:#aaa;
                  letter-spacing:.04em; line-height:1; cursor:pointer;
                  user-select:none; -webkit-user-select:none; }
                .xml-global-condense-label:hover, .xml-pill-layout-label:hover {
                  border-color:#777;
                  color:#ddd; }
                .xml-global-condense-toggle:focus-visible
                  + .xml-global-condense-label,
                .xml-pill-layout-toggle:focus-visible
                  + .xml-pill-layout-label { outline:1px solid #83c9ff;
                  outline-offset:.14em; }
                .xml-global-restore-text { display:none; }
                .xml-global-condense-toggle:checked
                  + .xml-global-condense-label .xml-global-contract-text {
                  display:none; }
                .xml-global-condense-toggle:checked
                  + .xml-global-condense-label .xml-global-restore-text {
                  display:inline; }
                .xml-pill-inline-text { display:none; }
                .xml-pill-layout-toggle:checked
                  + .xml-pill-layout-label .xml-pill-stack-text {
                  display:none; }
                .xml-pill-layout-toggle:checked
                  + .xml-pill-layout-label .xml-pill-inline-text {
                  display:inline; }
                .xml-tree, .xml-children { display:flex; flex-direction:column;
                  gap:.24em; list-style:none; margin:0; padding:0; }
                .xml-reader li { max-width:none; }
                .xml-node { position:relative; margin:0; }
                .xml-node details[open] > .xml-children { margin-top:.24em; }
                .xml-children > .xml-node::before { content:"";
                  position:absolute;
                  left:calc(var(--xml-indent, 0em) + .06em);
                  top:-.24em; bottom:-.24em;
                  z-index:1; border-left:1px solid #3f3f3f;
                  pointer-events:none; }
                .xml-children > .xml-node:last-child::before {
                  bottom:auto; height:calc(var(--xml-row-midline) + .24em); }
                .xml-node details { position:relative; margin:0; padding:0; }
                .xml-node summary { position:relative; cursor:pointer; list-style:none; }
                .xml-node summary::-webkit-details-marker { display:none; }
                .xml-node summary::before { content:""; position:absolute;
                  left:calc(1.55em + var(--xml-indent, 0em));
                  top:var(--xml-row-midline);
                  z-index:2; width:0; height:0;
                  border-top:.5em solid transparent;
                  border-bottom:.5em solid transparent;
                  border-left:.75em solid #777;
                  transform:translateY(-50%%); pointer-events:none; }
                .xml-node details[open] > summary::before {
                  border-top:.75em solid #777;
                  border-right:.5em solid transparent;
                  border-bottom:0; border-left:.5em solid transparent;
                  transform:translate(-.125em, -50%%); }
                .xml-row { position:relative; box-sizing:border-box; min-width:0;
                  min-height:var(--xml-row-height);
                  padding:.28em 0; border-radius:.25em; }
                .xml-nested-row::after { content:""; position:absolute;
                  left:calc(var(--xml-indent, 0em) + .06em);
                  top:var(--xml-row-midline);
                  z-index:1; width:2.44em; border-top:1px solid #3f3f3f;
                  pointer-events:none; }
                .xml-expandable > details > .xml-nested-row::after {
                  width:1.37em; }
                .xml-condense-toggle { position:absolute; width:1px;
                  height:1px; overflow:hidden; clip-path:inset(50%%);
                  white-space:nowrap; }
                .xml-condense-label { display:inline-block;
                  box-sizing:border-box; width:.9em; height:1.1em;
                  flex:0 0 auto; margin:.125em 0 0 .38em;
                  border:1px solid #444;
                  border-radius:.18em; background:#171717; color:#888;
                  font-size:1em; line-height:1em; text-align:center;
                  cursor:pointer; pointer-events:auto;
                  user-select:none; -webkit-user-select:none; }
                .xml-condense-label::before { content:"≡"; display:block;
                  transform:scale(.72); }
                .xml-condense-label:hover { border-color:#666; color:#bbb; }
                .xml-node details:has(> .xml-condense-toggle:checked)
                  > summary .xml-condense-label {
                  color:#b5cea8; }
                .xml-node details:has(> .xml-condense-toggle:checked)
                  > summary .xml-condense-label::before {
                  content:"⋯"; }
                .xml-node details:has(> .xml-condense-toggle:focus-visible)
                  > summary .xml-condense-label {
                  outline:1px solid #83c9ff; outline-offset:.12em; }
                .xml-condensed-value { display:none; min-width:0;
                  align-items:center; gap:.3em; overflow:hidden;
                  white-space:nowrap; }
                .xml-node details:has(> .xml-condense-toggle:checked)
                  > summary .xml-normal-value { display:none; }
                .xml-node details:has(> .xml-condense-toggle:checked)
                  > summary .xml-condensed-value { display:flex; }
                .xml-condense-toggle:checked ~ .xml-children
                  > .xml-condensable { display:none; }
                .xml-condense-toggle:checked
                  ~ .xml-children.xml-all-condensable { display:none; }
                .xml-condense-toggle:checked ~ .xml-children
                  > .xml-last-structural::before { bottom:auto;
                  height:calc(var(--xml-row-midline) + .24em); }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-node details:has(> .xml-condense-toggle)
                  > summary .xml-normal-value { display:none; }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-node details:has(> .xml-condense-toggle)
                  > summary .xml-condensed-value { display:flex; }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-children > .xml-condensable { display:none; }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-children.xml-all-condensable { display:none; }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-children > .xml-last-structural::before { bottom:auto;
                  height:calc(var(--xml-row-midline) + .24em); }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-condense-label { color:#b5cea8; }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-condense-label::before { content:"⋯"; }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-node details:has(> .xml-condense-toggle:checked)
                  > summary .xml-normal-value { display:inline; }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-node details:has(> .xml-condense-toggle:checked)
                  > summary .xml-condensed-value { display:none; }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-node details:has(> .xml-condense-toggle:checked)
                  > summary .xml-condense-label { color:#888; }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-node details:has(> .xml-condense-toggle:checked)
                  > summary .xml-condense-label::before { content:"≡"; }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-node details:has(> .xml-condense-toggle:checked)
                  > .xml-children > .xml-condensable { display:block; }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-node details:has(> .xml-condense-toggle:checked)
                  > .xml-children.xml-all-condensable { display:flex; }
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-node details:has(> .xml-condense-toggle:checked)
                  > .xml-children > .xml-last-structural:has(~ .xml-condensable)::before {
                  bottom:-.24em; height:auto; }
                .xml-row:hover { background:linear-gradient(to right,
                  transparent 0 calc(var(--xml-indent, 0em) + .35em),
                  #1b1b1b calc(var(--xml-indent, 0em) + .35em)); }
                .xml-row > * { position:relative; z-index:2; }
                .xml-tag-cell, .xml-value { min-width:0; overflow-wrap:anywhere; }
                .xml-tag-cell {
                  display:flex; align-items:flex-start;
                  padding-left:calc(var(--xml-indent, 0em) + 2.65em); }
                .xml-tag-cell::after { content:""; flex:1 1 auto;
                  min-width:.5em;
                  margin:calc(var(--xml-row-midline) - .28em) -1.2em 0 .45em;
                  border-top:1px dotted #4a4a4a; pointer-events:none; }
                .xml-node details[open]:has(
                  > .xml-children:not(.xml-all-condensable))
                  > summary > .xml-tag-cell::before,
                .xml-reader:not(:has(> .xml-heading
                  .xml-global-condense-toggle:checked))
                  .xml-node details[open]:has(
                    > .xml-condense-toggle:not(:checked)
                    ~ .xml-children.xml-all-condensable)
                  > summary > .xml-tag-cell::before,
                .xml-reader:has(> .xml-heading
                  .xml-global-condense-toggle:checked)
                  .xml-node details[open]:has(
                    > .xml-condense-toggle:checked
                    ~ .xml-children.xml-all-condensable)
                  > summary > .xml-tag-cell::before {
                  content:""; position:absolute;
                  left:calc(var(--xml-indent, 0em) + 1.9em);
                  top:calc(var(--xml-row-midline) - .28em + .65em);
                  bottom:-.52em;
                  z-index:1; border-left:1px solid #3f3f3f;
                  pointer-events:none; }
                .xml-tag { flex:0 1 auto; min-width:0;
                  white-space:nowrap;
                  color:#83c9ff; font-weight:700; }
                .xml-value { color:#ddd; }
                .xml-empty { color:#666; }
                .xml-property-name { color:#d7ba7d; }
                .xml-property-value { color:#b5cea8; }
                .xml-map-expand-toggle { position:absolute; width:1px;
                  height:1px; overflow:hidden; clip-path:inset(50%%);
                  white-space:nowrap; }
                .xml-map-pair { display:inline-grid; flex:0 0 auto;
                  grid-template-columns:14ch 28ch; column-gap:.35em;
                  box-sizing:border-box; max-width:100%%;
                  padding:.08em .5em; border:1px solid #444;
                  border-radius:.55em; background:#171717; cursor:pointer; }
                .xml-map-pair:hover { border-color:#666; }
                .xml-map-expand-toggle:focus-visible + .xml-map-pair {
                  outline:1px solid #83c9ff; outline-offset:.12em; }
                .xml-map-expand-toggle:checked + .xml-map-pair {
                  grid-template-columns:minmax(14ch, max-content)
                    minmax(28ch, max-content);
                  max-width:none; border-color:#666; }
                .xml-reader:has(> .xml-heading
                  .xml-pill-layout-toggle:checked) .xml-map-pair {
                  grid-template-columns:21ch; grid-template-rows:auto auto;
                  column-gap:0; row-gap:.08em; padding:.2em .5em; }
                .xml-reader:has(> .xml-heading
                  .xml-pill-layout-toggle:checked) .xml-map-key {
                  font-size:.5em; }
                .xml-reader:has(> .xml-heading
                  .xml-pill-layout-toggle:checked) .xml-empty {
                  display:inline-grid; grid-template-rows:auto auto;
                  row-gap:.08em; box-sizing:border-box;
                  padding:.2em .5em; border:1px solid transparent; }
                .xml-reader:has(> .xml-heading
                  .xml-pill-layout-toggle:checked) .xml-empty-mark {
                  grid-row:1; transform:translateY(-.2em); }
                .xml-reader:has(> .xml-heading
                  .xml-pill-layout-toggle:checked)
                  .xml-value:not(:has(> .xml-condensed-value))
                  > .xml-normal-value:has(> .xml-empty) {
                  display:flex; align-items:flex-start; }
                .xml-reader:has(> .xml-heading
                  .xml-pill-layout-toggle:checked) .xml-empty::after {
                  content:"\\00a0"; grid-row:2; font-size:.5em; }
                .xml-reader:has(> .xml-heading
                  .xml-pill-layout-toggle:checked)
                  .xml-map-expand-toggle:checked + .xml-map-pair {
                  grid-template-columns:minmax(21ch, max-content);
                  grid-template-rows:max-content max-content; }
                .xml-map-expand-toggle:checked + .xml-map-pair
                  .xml-map-key,
                .xml-map-expand-toggle:checked + .xml-map-pair
                  .xml-map-value { overflow:visible; text-overflow:clip; }
                .xml-condensed-value:has(> .xml-map-expand-toggle:checked) {
                  position:relative; z-index:3; overflow:visible; }
                .xml-map-key, .xml-map-value { min-width:0; overflow:hidden;
                  text-overflow:ellipsis; white-space:nowrap; }
                .xml-map-key { color:#d7ba7d; }
                .xml-map-value { color:#b5cea8; }
                .xml-limit { position:relative; padding:.3em .65em;
                  color:#888; font-style:italic; }
                .xml-error { margin-bottom:1em; padding:.75em 1em;
                  border-left:.25em solid #b98036; background:#1b1b1b;
                  color:#d8c19f; }
                %s
                .plain { margin:0; padding:0; background:transparent; }
                .error { color:#ff9b9b; padding:2em; }
                </style></head><body%s>%s</body></html>
                """, readerFamily,
                style.fontSize(), weight, fontStyle,
                style.lineSpacing(), style.letterSpacing(),
                style.wrap() ? "anywhere" : "normal", codeFamily,
                style.tabSize(), codeFamily, codeFamily,
                0.28 + style.lineSpacing() / 2.0,
                0.56 + style.lineSpacing(),
                syntaxCss, bodyClass, body);
    }

    /** CSS palette generated from SyntaxHighlighter's shared Source colors. */
    private static String syntaxCss() {
        StringBuilder css = new StringBuilder();
        for (HighlightRole token : HighlightRole.values()) {
            css.append(".syntax-")
                    .append(token.styleClass())
                    .append(" { color:")
                    .append(HighlightTheme.color(token))
                    .append("; }");
        }
        return css.toString();
    }

    public static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * Font family names reach a quoted CSS string inside {@code <style>}; keep
     * only characters that cannot end the string or the element.
     */
    private static String cssString(String value) {
        var out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_'
                    || c == '.') {
                out.append(c);
            }
        }
        return out.toString();
    }

    enum TableAlignment {
        NONE(""), LEFT("left"), CENTER("center"), RIGHT("right");

        private final String cssName;

        TableAlignment(String cssName) {
            this.cssName = cssName;
        }
    }

    record TableHeader(List<String> cells, List<TableAlignment> alignments) {
    }

    record TableBody(List<List<String>> rows, int lastLine) {
    }

    record InlineBlock(String html, int lastLine) {
    }

    record ListMarker(
            int indent, boolean ordered,
            String marker, String body) {
    }

    record ListBlock(
            List<ListNode> nodes, int lastLine) {
    }

    record Task(boolean checked, String text) {
    }

    private record XmlMapEntry(String key, String value) {
    }

    private static final class XmlRenderState {
        private int nodes;
        private boolean nodeLimitShown;
        private int compact;
        private boolean compactLimitShown;

        /** Adds a leaf pill within the per-document budget, then one "more" pill. */
        private void addCompact(List<XmlMapEntry> entries, XmlMapEntry entry) {
            if (compact < MAX_XML_COMPACT_ENTRIES) {
                compact++;
                entries.add(entry);
            } else if (!compactLimitShown) {
                compactLimitShown = true;
                entries.add(new XmlMapEntry("…", "more leaves are not shown"));
            }
        }
        private double maxTagWidthEm;

        private void recordTag(int depth, String tag, boolean branchControl) {
            double width = depth * XML_TREE_INDENT_EM + 2.65
                    + tag.codePointCount(0, tag.length()) * .65
                    + (branchControl ? 1.9 : 0);
            maxTagWidthEm = Math.max(maxTagWidthEm, width);
        }
    }

    static final class ListNode {
        private final int indent;
        private final boolean ordered;
        private final String marker;
        private final StringBuilder text;
        private final List<ListNode> children = new ArrayList<>();

        private ListNode(
                int indent, boolean ordered,
                String marker, String text) {
            this.indent = indent;
            this.ordered = ordered;
            this.marker = marker;
            this.text = new StringBuilder(text);
        }

        private int indent() {
            return indent;
        }

        boolean ordered() {
            return ordered;
        }

        String marker() {
            return marker;
        }

        String text() {
            return text.toString();
        }

        List<ListNode> children() {
            return children;
        }

        private void appendContinuation(String continuation) {
            if (!text.isEmpty() && !continuation.isEmpty()) {
                text.append(' ');
            }
            text.append(continuation);
        }

        boolean containsTask() {
            if (task(text()) != null) return true;
            return children.stream().anyMatch(ListNode::containsTask);
        }
    }

}
