package io.github.ghosthack.docview.fx;

import io.github.ghosthack.docview.HighlightEngines;
import io.github.ghosthack.docview.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlockViewTest {

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
}
