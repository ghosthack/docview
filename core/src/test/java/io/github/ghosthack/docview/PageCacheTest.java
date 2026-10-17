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

    @Test
    void hashCollidingEditsAreDistinctPages() throws Exception {
        // "Aa" and "BB" share a String hash code, length and UTF-8 size.
        DocumentContent before = DocumentContent.of(Path.of("e.md"), DocumentKind.MARKDOWN, "Aa");
        DocumentContent after = DocumentContent.of(Path.of("e.md"), DocumentKind.MARKDOWN, "BB");
        pages.request(PageCache.Key.of(before, STYLE, HighlightEngines.NONE), before)
                .get(10, TimeUnit.SECONDS);

        String page = pages.request(PageCache.Key.of(after, STYLE, HighlightEngines.NONE), after)
                .get(10, TimeUnit.SECONDS);

        assertTrue(page.contains("BB"));
        assertFalse(page.contains("Aa"));
    }

    @Test
    void keysDigestTheTextInsteadOfRetainingIt() {
        String text = "x".repeat(100_000);
        var key = PageCache.Key.of(markdown("g.md", text), STYLE, HighlightEngines.NONE);
        var same = PageCache.Key.of(markdown("g.md", new String(text)), STYLE,
                HighlightEngines.NONE);
        // A lone surrogate and '?' encode alike in UTF-8, but not as UTF-16 units.
        var surrogate = PageCache.Key.of(markdown("g.md", "\uD800"), STYLE,
                HighlightEngines.NONE);
        var question = PageCache.Key.of(markdown("g.md", "?"), STYLE, HighlightEngines.NONE);

        assertTrue(key.toString().length() < 1_000, "the key does not hold the text");
        assertEquals(key, same);
        assertNotEquals(surrogate, question);
    }

    @Test
    void closeCompletesEveryPendingRequest() {
        DocumentContent big = markdown("big.md", "```java\nclass A {}\n```\n".repeat(5_000));
        DocumentContent small = markdown("small.md", "small");
        CompletableFuture<String> running =
                pages.request(PageCache.Key.of(big, STYLE, HighlightEngines.AUTOMATIC), big);
        CompletableFuture<String> queued =
                pages.request(PageCache.Key.of(small, STYLE, HighlightEngines.NONE), small);

        pages.close();

        assertTrue(running.isCancelled());
        assertTrue(queued.isCancelled());
    }

    @Test
    void requestsAfterCloseAreCancelledNotLeftPending() {
        DocumentContent doc = markdown("f.md", "text");
        var key = PageCache.Key.of(doc, STYLE, HighlightEngines.NONE);
        pages.close();

        assertTrue(pages.request(key, doc).isCancelled());
        assertTrue(pages.request(key, doc).isCancelled());
        assertTrue(pages.prefetch(key, doc).isCancelled());
        assertFalse(pages.isCached(key));
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
