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
    void scrollMemorySurvivesShowsBetweenLayoutPasses() {
        Fx.run(() -> {
            var view = new XmlTreeView();
            var root = new javafx.scene.layout.StackPane(view.node());
            new javafx.scene.Scene(root, 800, 600);
            var style = ReaderStyle.defaults();
            var source = new StringBuilder("<r>");
            for (int i = 0; i < 500; i++) source.append("<e a='1'><c>x</c><d><f>1</f></d></e>");
            String text = source.append("</r>").toString();
            XmlTree.Tree x = XmlTree.parse(text), y = XmlTree.parse(text), z = XmlTree.parse(text);

            view.show(Path.of("x.xml"), x, style, text);
            root.applyCss();
            root.layout();
            table(view).scrollTo(200);
            root.layout();
            int rowOfX = BlockViewTest.firstVisible(table(view));
            assertTrue(rowOfX > 150, "X is scrolled down");
            view.show(Path.of("y.xml"), y, style, text);
            view.show(Path.of("z.xml"), z, style, text);
            root.layout();
            view.show(Path.of("y.xml"), y, style, text);
            root.layout();
            assertEquals(0, BlockViewTest.firstVisible(table(view)), "Y was never scrolled");
            view.show(Path.of("x.xml"), x, style, text);
            root.layout();
            assertEquals(rowOfX, BlockViewTest.firstVisible(table(view)), "X keeps its row");
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
