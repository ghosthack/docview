package io.github.ghosthack.docview;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PageCacheTest {

    private static final ReaderStyle STYLE =
            new ReaderStyle("serif", "monospace", 15,
                    false, false, 1.4, 0, true, 4);

    private final PageCache pages = new PageCache();

    @AfterEach
    void close() {
        pages.close();
    }

    @Test
    void buildsInBackgroundThenServesFromCache() throws Exception {
        DocumentContent doc = markdown("a.md", "# Title\n\nText.");
        var key = PageCache.Key.of(doc, STYLE, HighlightEngines.NONE);

        String built = pages.request(key, doc).get(10, TimeUnit.SECONDS);
        CompletableFuture<String> again = pages.request(key, doc);

        assertTrue(again.isDone(), "second request is served synchronously");
        assertSame(built, again.join());
        assertEquals(SafeMarkupRenderer.page(doc, STYLE, HighlightEngines.NONE), built);
    }

    @Test
    void prefetchedPageIsReadyForTheLaterRequest() throws Exception {
        DocumentContent doc = markdown("b.md", "- [ ] task\n");
        var key = PageCache.Key.of(doc, STYLE, HighlightEngines.NONE);

        pages.prefetch(key, doc);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!pages.isCached(key) && System.nanoTime() < deadline) Thread.sleep(5);

        assertTrue(pages.request(key, doc).isDone());
    }

    @Test
    void styleChangesAreDistinctPages() throws Exception {
        DocumentContent doc = markdown("c.md", "Text.");
        var larger = new ReaderStyle("serif", "monospace", 20,
                false, false, 1.4, 0, true, 4);

        String small = pages.request(PageCache.Key.of(doc, STYLE, HighlightEngines.NONE), doc)
                .get(10, TimeUnit.SECONDS);
        CompletableFuture<String> big =
                pages.request(PageCache.Key.of(doc, larger, HighlightEngines.NONE), doc);

        assertNotEquals(small, big.get(10, TimeUnit.SECONDS));
    }

    @Test
    void evictsLeastRecentlyUsedBeyondTheEntryLimit() throws Exception {
        DocumentContent first = markdown("0.md", "zero");
        var firstKey = PageCache.Key.of(first, STYLE, HighlightEngines.NONE);
        pages.request(firstKey, first).get(10, TimeUnit.SECONDS);
        for (int i = 1; i <= PageCache.MAX_ENTRIES; i++) {
            DocumentContent doc = markdown(i + ".md", "doc " + i);
            pages.request(PageCache.Key.of(doc, STYLE, HighlightEngines.NONE), doc)
                    .get(10, TimeUnit.SECONDS);
        }

        assertFalse(pages.isCached(firstKey));
    }

    @Test
    void concurrentRequestsShareOneBuild() {
        DocumentContent doc = markdown("d.md", "```java\nclass A {}\n```\n".repeat(200));
        var key = PageCache.Key.of(doc, STYLE, HighlightEngines.AUTOMATIC);

        CompletableFuture<String> one = pages.request(key, doc);
        CompletableFuture<String> two = pages.request(key, doc);

        assertSame(one.join(), two.join());
    }

    private static DocumentContent markdown(String name, String text) {
        int lines = (int) text.chars().filter(c -> c == '\n').count() + 1;
        int[] starts = new int[lines];
        for (int i = 0, line = 1; i < text.length(); i++) {
            if (text.charAt(i) == '\n') starts[line++] = i + 1;
        }
        return new DocumentContent(Path.of(name), DocumentKind.MARKDOWN, text,
                StandardCharsets.UTF_8, text.length(), 1L, starts);
    }
}
