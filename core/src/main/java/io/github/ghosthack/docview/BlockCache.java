package io.github.ghosthack.docview;

import java.nio.file.Path;

/**
 * Native Reader row models, parsed in the background by
 * {@link Blocks#parse} for Markdown or {@link HtmlBlockParser#parse} for
 * HTML; see {@link BackgroundCache}. A model does not depend on fonts
 * or zoom, so restyling reuses it. Evicting a model only releases its syntax
 * sessions, which reopen on demand if it is still on screen.
 */
public final class BlockCache
        extends BackgroundCache<BlockCache.Key, Blocks.Model> {

    /**
     * Everything a model depends on. The text length and hash keep two
     * in-memory versions of one path (same size, no modification time) apart.
     */
    public record Key(Path path, long size, long modified, int textLength, int textHash,
                      DocumentKind kind, String highlightEngine) {

        public static Key of(DocumentContent document, String highlightEngine) {
            return new Key(document.path(), document.size(),
                    document.lastModifiedMillis(), document.text().length(),
                    document.text().hashCode(), document.kind(), highlightEngine);
        }
    }

    static final int MAX_ENTRIES = 8;
    /** Rows across cached models; a 400 KB document is roughly 15k rows. */
    static final long MAX_ROWS = 400_000;

    public BlockCache() {
        super("markdown-model", MAX_ENTRIES, MAX_ROWS,
                (key, document) -> key.kind() == DocumentKind.HTML
                        ? Blocks.fromHtml(document.text())
                        : Blocks.fromMarkdown(document.text(), key.highlightEngine()),
                model -> model.rows().size(), Blocks.Model::close);
    }
}
