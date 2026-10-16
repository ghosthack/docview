package io.github.ghosthack.docview;

import io.github.ghosthack.docview.ReaderStyle;

import java.nio.file.Path;

/**
 * Sanitized HTML pages for a WebView, built by {@link SafeMarkupRenderer#page} in
 * the background; see {@link BackgroundCache}.
 */
public final class PageCache extends BackgroundCache<PageCache.Key, String> {

    /**
     * Everything a page's HTML depends on. The text length and hash keep two
     * in-memory versions of one path (same size, no modification time) apart.
     */
    public record Key(Path path, long size, long modified, int textLength, int textHash,
                      DocumentKind kind, ReaderStyle style, String highlightEngine) {

        public static Key of(DocumentContent document, ReaderStyle style,
                             String highlightEngine) {
            return new Key(document.path(), document.size(),
                    document.lastModifiedMillis(), document.text().length(),
                    document.text().hashCode(), document.kind(), style,
                    highlightEngine);
        }
    }

    static final int MAX_ENTRIES = 8;
    /** About 48 MiB of UTF-16, far above typical pages; large ones evict early. */
    static final long MAX_CHARS = 24L * 1024 * 1024;

    public PageCache() {
        super("reader-page", MAX_ENTRIES, MAX_CHARS,
                (key, document) -> SafeMarkupRenderer.page(
                        document, key.style(), key.highlightEngine()),
                String::length, ignored -> { });
    }
}
