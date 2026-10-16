package io.github.ghosthack.docview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class MarkdownOutlineTest {

    @Test
    void extractsReaderHeadingsWithLevelsLabelsAndStableAnchors() {
        List<MarkdownOutline.Heading> headings = MarkdownOutline.headings("""
                # Overview
                ## **Install** [locally](setup.md)
                #### `Advanced` options
                ## snake_case stays readable
                """);

        assertEquals(List.of(
                new MarkdownOutline.Heading(1, "Overview", "md-heading-0"),
                new MarkdownOutline.Heading(2, "Install locally", "md-heading-1"),
                new MarkdownOutline.Heading(4, "Advanced options", "md-heading-2"),
                new MarkdownOutline.Heading(2, "snake_case stays readable",
                        "md-heading-3")),
                headings);
    }

    @Test
    void ignoresHeadingSyntaxInsideFencedCodeAndQuotes() {
        List<MarkdownOutline.Heading> headings = MarkdownOutline.headings("""
                # Included
                ```markdown
                ## Not a heading
                ```
                > ## Quoted material
                ### Included too
                """);

        assertEquals(List.of(
                new MarkdownOutline.Heading(1, "Included", "md-heading-0"),
                new MarkdownOutline.Heading(3, "Included too", "md-heading-1")),
                headings);
    }
}
