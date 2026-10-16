package io.github.ghosthack.docview;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DocumentContentTest {

    @Test
    void ofComputesLineOffsetsAndUtf8Size() {
        DocumentContent content = DocumentContent.of(
                Path.of("notes.md"), DocumentKind.MARKDOWN, "a\nbé\n");

        assertEquals(3, content.lineCount());
        assertEquals(2, content.lineStart(1));
        assertEquals("bé", content.text().substring(
                content.lineStart(1), content.lineEnd(1)));
        assertEquals(6, content.size());
    }

    @Test
    void defaultReaderStyleIsTheReaderBaseline() {
        var style = ReaderStyle.defaults();

        assertEquals(15, style.fontSize());
        assertEquals(4, style.tabSize());
    }
}
