package io.github.ghosthack.docview;

import io.github.ghosthack.docview.XmlTree.Entry;
import io.github.ghosthack.docview.XmlTree.Kind;
import io.github.ghosthack.docview.XmlTree.XmlNode;
import org.junit.jupiter.api.Test;

import java.util.List;

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
