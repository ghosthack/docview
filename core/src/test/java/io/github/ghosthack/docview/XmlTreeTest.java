package io.github.ghosthack.docview;

import io.github.ghosthack.docview.XmlTree.Entry;
import io.github.ghosthack.docview.XmlTree.Kind;
import io.github.ghosthack.docview.XmlTree.XmlNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class XmlTreeTest {

    @Test
    void attributesAndLeafChildrenBecomePillsInDocumentOrder() {
        XmlNode root = XmlTree.parse("<book id=\"7\"><title>Dune</title>"
                + "<authors><author>Frank</author></authors><year/></book>").root();

        assertEquals("book", root.tag());
        assertEquals(List.of(new Entry("id", "7"), new Entry("title", "Dune"),
                new Entry("year", "—")), root.compact());
        List<XmlNode> children = root.children();
        assertEquals(Kind.PROPERTY, children.get(0).kind());
        assertEquals(List.of("id", "title", "authors", "year"),
                children.stream().map(XmlNode::tag).toList());
        assertTrue(children.get(1).leaf());
        assertFalse(children.get(2).leaf());
    }

    @Test
    void branchesWithStructureStartOpenAndAllLeafBranchesClosed() {
        XmlNode root = XmlTree.parse("<r><group><item>a</item></group>"
                + "<nest><inner><x>1</x></inner></nest><meta k=\"v\"/></r>").root();

        assertTrue(root.expandedByDefault());
        // Only leaf children: closed, with its leaves shown as pills.
        XmlNode group = root.children().get(0);
        assertFalse(group.expandedByDefault());
        assertTrue(group.children().get(0).leaf());
        // A structural child keeps the branch open.
        assertTrue(root.children().get(1).expandedByDefault());
        XmlNode meta = root.children().get(2);
        assertFalse(meta.expandedByDefault());
        assertEquals(List.of(new Entry("k", "v")), meta.compact());
    }

    @Test
    void directValuesCollapseWhitespaceLikeTheWebTree() {
        XmlNode root = XmlTree.parse("<p>  hello\n   world <![CDATA[ x ]]></p>").root();

        assertEquals("hello world x", root.value());
    }

    @Test
    void malformedXmlReportsAnError() {
        XmlTree.Tree tree = XmlTree.parse("<open><unclosed></open>");

        assertNull(tree.root());
        assertNotNull(tree.error());
    }

    @Test
    void externalEntitiesAreNotResolved() {
        XmlTree.Tree tree = XmlTree.parse("<?xml version=\"1.0\"?>"
                + "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>");

        if (tree.root() != null) assertFalse(tree.root().value().contains("root:"));
    }

    @Test
    void internalEntitiesAreExpandedIntoTextAndElements() {
        XmlNode root = XmlTree.parse("<!DOCTYPE r [<!ENTITY e \"hello\">"
                + "<!ENTITY b \"<b>bold</b>\">]>"
                + "<r><k>&e; world</k><j>x&e;y</j><v>&b;</v></r>").root();

        assertEquals("hello world", root.children().get(0).value());
        assertEquals("xhelloy", root.children().get(1).value());
        XmlNode v = root.children().get(2);
        assertEquals(List.of("b"), v.children().stream().map(XmlNode::tag).toList());
        assertEquals("bold", v.children().get(0).value());
    }

    @Test
    void entityExpansionBombsAreRefused() {
        var xml = new StringBuilder("<!DOCTYPE r [<!ENTITY l0 \"lol\">");
        for (int i = 1; i <= 9; i++) {
            xml.append("<!ENTITY l").append(i).append(" \"")
                    .append(("&l" + (i - 1) + ";").repeat(10)).append("\">");
        }
        XmlTree.Tree tree = XmlTree.parse(xml.append("]><r>&l9;</r>").toString());

        assertNull(tree.root());
        assertNotNull(tree.error());
    }

    @Test
    void nestingBeyondTheDepthCapShowsALimitRowInsteadOfFailing() {
        int depth = SafeMarkupRenderer.MAX_XML_TREE_DEPTH + 72;
        XmlTree.Tree tree = XmlTree.parse("<a>".repeat(depth) + "</a>".repeat(depth));

        assertNull(tree.error());
        XmlNode node = tree.root();
        while (node.kind() == Kind.ELEMENT) node = node.children().getFirst();
        assertEquals("Deeper nodes are not shown", node.tag());
        assertEquals(SafeMarkupRenderer.MAX_XML_TREE_DEPTH + 1, node.depth());
    }

    @Test
    void manyAttributesStayProperties() {
        var xml = new StringBuilder("<r");
        for (int i = 0; i < 300; i++) xml.append(" a").append(i).append("=\"v\"");
        XmlTree.Tree tree = XmlTree.parse(xml.append("/>").toString());

        assertNull(tree.error());
        assertEquals(300, tree.root().children().size());
    }

    @Test
    void leadingByteOrderMarkIsIgnored() {
        XmlTree.Tree tree = XmlTree.parse("\uFEFF<r>x</r>");

        assertNull(tree.error());
        assertEquals("x", tree.root().value());
    }

    @Test
    void cacheKeepsHashCollidingEditsApart() throws Exception {
        // "Aa" and "BB" share a String hash code, length and UTF-8 size.
        var before = DocumentContent.of(Path.of("c.xml"), DocumentKind.XML, "<r>Aa</r>");
        var after = DocumentContent.of(Path.of("c.xml"), DocumentKind.XML, "<r>BB</r>");
        try (var cache = new XmlTreeCache()) {
            cache.request(XmlTreeCache.Key.of(before), before).get(10, TimeUnit.SECONDS);
            XmlTree.Tree tree = cache.request(XmlTreeCache.Key.of(after), after)
                    .get(10, TimeUnit.SECONDS);

            assertEquals("BB", tree.root().value());
        }
    }

    @Test
    void cacheKeyDoesNotRetainTheText() {
        String text = "<r>" + "x".repeat(100_000) + "</r>";
        var key = XmlTreeCache.Key.of(DocumentContent.of(Path.of("k.xml"), DocumentKind.XML, text));

        assertTrue(key.toString().length() < 1_000);
        assertEquals(key, XmlTreeCache.Key.of(
                DocumentContent.of(Path.of("k.xml"), DocumentKind.XML, new String(text))));
    }

    @Test
    void nodeLimitAddsOneLimitRow() {
        var xml = new StringBuilder("<r>");
        for (int i = 0; i < SafeMarkupRenderer.MAX_XML_TREE_NODES + 50; i++) {
            xml.append("<i a=\"").append(i).append("\"/>");
        }
        XmlTree.Tree tree = XmlTree.parse(xml.append("</r>").toString());

        // Like the web tree, the single limit row appears wherever the limit hits.
        assertEquals(1, countLimits(tree.root()));
        assertTrue(tree.nodes() <= SafeMarkupRenderer.MAX_XML_TREE_NODES);
    }

    private static int countLimits(XmlNode node) {
        int count = node.kind() == Kind.LIMIT ? 1 : 0;
        for (XmlNode child : node.children()) count += countLimits(child);
        return count;
    }
}
