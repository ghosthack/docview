package io.github.ghosthack.docview.fx;

import io.github.ghosthack.docview.ReaderStyle;
import io.github.ghosthack.docview.XmlTree;
import io.github.ghosthack.docview.XmlTree.XmlNode;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class XmlTreeViewTest {

    @Test
    void rememberedDocumentFollowsLeafToggleChangedElsewhere() {
        Fx.run(() -> {
            var view = new XmlTreeView();
            var style = ReaderStyle.defaults();
            String a = "<r a=\"1\" c=\"2\"><x><y/></x></r>";
            XmlTree.Tree treeA = XmlTree.parse(a);
            view.show(Path.of("a.xml"), treeA, style, a);
            TreeTableView<XmlNode> table = table(view);
            table.getSelectionModel().select(0);
            assertEquals("r\ta: 1; c: 2", view.selectedText());

            String b = "<s/>";
            view.show(Path.of("b.xml"), XmlTree.parse(b), style, b);
            contractLeaves(view).setSelected(false);
            view.show(Path.of("a.xml"), treeA, style, a);

            // Restored leaves are rows again, not pills.
            assertEquals(3, table.getRoot().getChildren().size());
            table.getSelectionModel().select(0);
            assertEquals("r\t", view.selectedText());
        });
    }

    @Test
    void clearRemovesParseErrorPanel() {
        Fx.run(() -> {
            var view = new XmlTreeView();
            String text = "<r><unclosed></r> SECRET-SOURCE";
            view.show(Path.of("bad.xml"), XmlTree.parse(text), ReaderStyle.defaults(), text);
            var root = (BorderPane) view.node();
            assertFalse(root.getTop().isVisible());

            view.clear();

            assertInstanceOf(TreeTableView.class, root.getCenter());
            assertTrue(root.getTop().isVisible());
            assertTrue(root.getTop().isManaged());
            assertTrue(root.lookupAll(".text-area").stream()
                    .noneMatch(node -> node instanceof TextArea));
        });
    }

    @SuppressWarnings("unchecked")
    private static TreeTableView<XmlNode> table(XmlTreeView view) {
        return (TreeTableView<XmlNode>) ((BorderPane) view.node()).getCenter();
    }

    private static ToggleButton contractLeaves(XmlTreeView view) {
        for (Node node : view.node().lookupAll(".toggle-button")) {
            if (node instanceof ToggleButton toggle && toggle.getText().endsWith("leaves")) {
                return toggle;
            }
        }
        throw new AssertionError("no leaf toggle");
    }
}
