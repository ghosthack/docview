package io.github.ghosthack.docview;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Immutable XML tree for the native XML Reader, built with the same secure
 * parser and the same rules as the WebView XML tree
 * ({@code SafeMarkupRenderer.xmlBody}): element rows show their direct text,
 * attributes become property rows, and leaf children (attributes and
 * text-only elements) can be "contracted" into key/value pills on their parent
 * row. The node and depth limits match too.
 */
public final class XmlTree {

    public enum Kind { ELEMENT, PROPERTY, LIMIT }

    /** A contracted leaf shown as a pill on its parent row. */
    public record Entry(String key, String value) {
    }

    /**
     * One row. {@code children} are in document order (attributes first);
     * {@link #leaf()} children are the contractible ones.
     */
    public record XmlNode(Kind kind, String tag, String value, int depth,
                          List<XmlNode> children, List<Entry> compact,
                          boolean leaf, boolean expandedByDefault) {

        /** Has children that stay rows even while leaves are contracted. */
        public boolean hasStructuralChildren() {
            for (XmlNode child : children) if (!child.leaf()) return true;
            return false;
        }
    }

    /** {@code root} is null when parsing failed; {@code error} explains why. */
    public record Tree(XmlNode root, String error, double tagColumnEm, int nodes) {
    }

    private XmlTree() {
    }

    public static Tree parse(String xml) {
        try {
            Element root = SafeMarkupRenderer.parseXmlRoot(xml);
            var state = new State();
            XmlNode node = element(root, 0, state);
            return new Tree(node, null,
                    Math.max(SafeMarkupRenderer.XML_MIN_TAG_COLUMN_EM, state.maxTagWidthEm),
                    state.nodes);
        } catch (ParserConfigurationException | SAXException | IOException
                | IllegalArgumentException exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) message = "The XML could not be parsed.";
            return new Tree(null, message, SafeMarkupRenderer.XML_MIN_TAG_COLUMN_EM, 0);
        }
    }

    private static final class State {
        int nodes;
        boolean limitShown;
        int compact;
        boolean compactLimitShown;

        /** Adds a leaf pill within the per-document budget, then one "more" pill. */
        void addCompact(List<Entry> entries, Entry entry) {
            if (compact < SafeMarkupRenderer.MAX_XML_COMPACT_ENTRIES) {
                compact++;
                entries.add(entry);
            } else if (!compactLimitShown) {
                compactLimitShown = true;
                entries.add(new Entry("…", "more leaves are not shown"));
            }
        }
        double maxTagWidthEm;

        /** Same estimate as the WebView tree's tag column. */
        void recordTag(int depth, String tag, boolean branchControl) {
            maxTagWidthEm = Math.max(maxTagWidthEm,
                    depth * SafeMarkupRenderer.XML_TREE_INDENT_EM + 2.65
                            + tag.codePointCount(0, tag.length()) * .65
                            + (branchControl ? 1.9 : 0));
        }

        /** The node-limit row, emitted once; null afterwards. */
        XmlNode limit(int depth) {
            if (limitShown) return null;
            limitShown = true;
            return new XmlNode(Kind.LIMIT, "More nodes are not shown", "", depth,
                    List.of(), List.of(), false, false);
        }
    }

    /** Mirrors {@code appendXmlElement}. Returns null past the node limit. */
    private static XmlNode element(Element element, int depth, State state) {
        if (state.nodes >= SafeMarkupRenderer.MAX_XML_TREE_NODES) return state.limit(depth);
        state.nodes++;
        var elements = new ArrayList<Element>();
        for (Node child = element.getFirstChild(); child != null;
                child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) elements.add((Element) child);
        }
        boolean depthLimited = !elements.isEmpty()
                && depth >= SafeMarkupRenderer.MAX_XML_TREE_DEPTH;
        boolean expandable = element.hasAttributes() || !elements.isEmpty();
        state.recordTag(depth, element.getTagName(), expandable);

        var compact = new ArrayList<Entry>();
        var children = new ArrayList<XmlNode>();
        for (int index = 0; index < element.getAttributes().getLength(); index++) {
            Node attribute = element.getAttributes().item(index);
            state.addCompact(compact, new Entry(attribute.getNodeName(),
                    attribute.getNodeValue()));
            XmlNode property = property(attribute, depth + 1, state);
            if (property != null) children.add(property);
        }
        boolean structural = false;
        if (depthLimited) {
            children.add(new XmlNode(Kind.LIMIT, "Deeper nodes are not shown", "",
                    depth + 1, List.of(), List.of(), false, false));
            structural = true;
        } else {
            for (Element child : elements) {
                boolean leafChild = !SafeMarkupRenderer.isXmlExpandable(child);
                if (leafChild) {
                    String value = SafeMarkupRenderer.directXmlValue(child);
                    state.addCompact(compact,
                            new Entry(child.getTagName(), value.isEmpty() ? "—" : value));
                } else {
                    structural = true;
                }
                XmlNode node = element(child, depth + 1, state);
                if (node != null) children.add(node);
            }
        }
        return new XmlNode(Kind.ELEMENT, element.getTagName(),
                SafeMarkupRenderer.directXmlValue(element), depth,
                List.copyOf(children), List.copyOf(compact), !expandable, structural);
    }

    private static XmlNode property(Node attribute, int depth, State state) {
        if (state.nodes >= SafeMarkupRenderer.MAX_XML_TREE_NODES) return state.limit(depth);
        state.nodes++;
        state.recordTag(depth, attribute.getNodeName(), false);
        return new XmlNode(Kind.PROPERTY, attribute.getNodeName(),
                attribute.getNodeValue(), depth, List.of(), List.of(), true, false);
    }
}
