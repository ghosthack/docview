package io.github.ghosthack.docview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DocumentClassifierTest {

    @Test
    void classifiesFirstClassDocumentFamilies() {
        assertEquals(DocumentKind.JAVA,
                DocumentClassifier.classify(Path.of("Thing.java")).orElseThrow());
        assertEquals(DocumentKind.MARKDOWN,
                DocumentClassifier.classify(Path.of("README.md")).orElseThrow());
        assertEquals(DocumentKind.HTML,
                DocumentClassifier.classify(Path.of("page.xhtml")).orElseThrow());
        assertEquals(DocumentKind.YAML,
                DocumentClassifier.classify(Path.of("config.yml")).orElseThrow());
        assertEquals(DocumentKind.XML,
                DocumentClassifier.classify(Path.of("drawing.svg")).orElseThrow());
        assertEquals(DocumentKind.RUBY,
                DocumentClassifier.classify(Path.of("table.rb")).orElseThrow());
        assertEquals(DocumentKind.TCL,
                DocumentClassifier.classify(
                        Path.of("lofi_file_browser.tcl")).orElseThrow());
    }

    @Test
    void recognizesImportantExtensionlessAndDotFiles() {
        assertEquals(DocumentKind.PLAIN,
                DocumentClassifier.classify(Path.of("LICENSE")).orElseThrow());
        assertEquals(DocumentKind.PROPERTIES,
                DocumentClassifier.classify(Path.of(".editorconfig")).orElseThrow());
        assertEquals(DocumentKind.SCRIPT,
                DocumentClassifier.classify(Path.of("Dockerfile")).orElseThrow());
    }

    @Test
    void skipsMediaAndUnknownBinaryNames() {
        assertTrue(DocumentClassifier.classify(Path.of("photo.jpg")).isEmpty());
        assertTrue(DocumentClassifier.classify(Path.of("movie.mp4")).isEmpty());
        assertTrue(DocumentClassifier.classify(Path.of("archive.bin")).isEmpty());
    }
}
