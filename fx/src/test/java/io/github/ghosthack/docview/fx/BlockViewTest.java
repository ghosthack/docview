package io.github.ghosthack.docview.fx;

import io.github.ghosthack.docview.HighlightEngines;
import io.github.ghosthack.docview.Blocks;
import io.github.ghosthack.docview.ReaderStyle;
import javafx.event.EventType;
import javafx.geometry.Point3D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlockViewTest {

    private BlockView view;
    private Text link;
    private final List<String> activated = new ArrayList<>();

    @Test
    void copiesOmitMarkersAndLineNumbers() {
        List<Blocks.Row> rows = Blocks.fromMarkdown(
                "# Title\n\n- [x] done `x`\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n"
                        + "```java\nint y;\n```\n\n---\n",
                HighlightEngines.NONE).rows();

        assertEquals("Title", BlockView.rowText(rows.get(0)));
        assertEquals("done x", BlockView.rowText(rows.get(1)));
        assertEquals("a\tb", BlockView.rowText(rows.get(2)));
        assertEquals("1\t2", BlockView.rowText(rows.get(3)));
        assertEquals("int y;", BlockView.rowText(rows.get(4)));
        assertEquals("", BlockView.rowText(rows.get(5)));
    }

    @Test
    void positionsOrderByRowThenOffset() {
        var early = new BlockView.Position(1, 9);
        var late = new BlockView.Position(2, 0);

        assertTrue(early.compareTo(late) < 0);
        assertTrue(new BlockView.Position(2, 3).compareTo(late) > 0);
    }

    @Test
    void codeLinesUseStyleTabSize() {
        var style = new ReaderStyle("Iowan Old Style", "SF Mono", 15, false, false,
                1.35, 0, true, 2);
        Fx.run(() -> {
            BlockView view = shown(Blocks.fromMarkdown("```\n\tx\n```\n", HighlightEngines.NONE),
                    style);
            List<TextFlow> flows = nodes(view.node(), TextFlow.class);
            assertFalse(flows.isEmpty());
            for (TextFlow flow : flows) assertEquals(2, flow.getTabSize());
        });
    }

    @Test
    void italicRunsAndHeadingsKeepStyleWeightAndPosture() {
        var bold = new ReaderStyle("Iowan Old Style", "SF Mono", 15, true, false, 1.35, 0, true, 4);
        var italic = new ReaderStyle("Iowan Old Style", "SF Mono", 15, false, true, 1.35, 0, true, 4);
        Fx.run(() -> {
            // em is bold italic; with a bold style, i matches it.
            BlockView html = shown(Blocks.fromHtml("<p><i>word</i> <em>both</em></p>"), bold);
            assertEquals(text(html, "both").getFont(), text(html, "word").getFont());

            // With an italic style, a heading is as italic as its em text.
            BlockView heading = shown(Blocks.fromMarkdown("# Title *both*\n",
                    HighlightEngines.NONE), italic);
            assertEquals(text(heading, "both").getFont(), text(heading, "Title ").getFont());
        });
    }

    @Test
    void releaseActivatesOnlyTheLinkThatWasPressed() {
        Fx.run(() -> {
            view = shown(Blocks.fromMarkdown("[go](https://example.com)\n", HighlightEngines.NONE),
                    ReaderStyle.defaults());
            view.setLinkActivated(activated::add);
            link = text(view, "go");
            // Press on the scroll bar, release over the link: a scroll, not a click.
            click(new ScrollBar(), link, 1);
        });
        Fx.run(() -> { });
        assertEquals(List.of(), activated);

        Fx.run(() -> click(link, link, 1));
        Fx.run(() -> { });
        assertEquals(List.of("https://example.com"), activated);

        // The second press of a handled double-click is not another link click.
        Fx.run(() -> {
            activated.clear();
            view.setOnDoubleClick(() -> { });
            click(link, link, 1);
            click(link, link, 2);
        });
        Fx.run(() -> { });
        assertEquals(List.of("https://example.com"), activated);
    }

    private void click(Node pressed, Node released, int clicks) {
        mouse(MouseEvent.MOUSE_PRESSED, pressed, clicks);
        mouse(MouseEvent.MOUSE_RELEASED, released, clicks);
    }

    private void mouse(EventType<MouseEvent> type, Node target, int clicks) {
        var pick = new PickResult(target, Point3D.ZERO, 0);
        view.node().fireEvent(new MouseEvent(type, 0, 0, 0, 0, MouseButton.PRIMARY, clicks,
                false, false, false, false, type == MouseEvent.MOUSE_PRESSED, false, false,
                false, false, true, pick));
    }

    private static BlockView shown(Blocks.Model model, ReaderStyle style) {
        var view = new BlockView();
        var root = new StackPane(view.node());
        new Scene(root, 800, 600);
        view.show(Path.of("doc.md"), model, style);
        root.applyCss();
        root.layout();
        return view;
    }

    private static Text text(BlockView view, String value) {
        for (Text text : nodes(view.node(), Text.class)) {
            if (text.getText().equals(value)) return text;
        }
        throw new AssertionError("no text " + value);
    }

    private static <T extends Node> List<T> nodes(Node root, Class<T> type) {
        var found = new ArrayList<T>();
        if (type.isInstance(root)) found.add(type.cast(root));
        if (root instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) found.addAll(nodes(child, type));
        }
        return found;
    }
}
