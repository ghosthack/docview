package io.github.ghosthack.docview;

import java.nio.file.Path;

/**
 * Native XML Reader trees parsed by {@link XmlTree#parse} in the background;
 * see {@link BackgroundCache}.
 */
public final class XmlTreeCache extends BackgroundCache<XmlTreeCache.Key, XmlTree.Tree> {

    /**
     * The text's SHA-256 keeps two in-memory versions of one path apart
     * without the key pinning the text, which the node budget does not count.
     */
    public record Key(Path path, long size, long modified, String textSha256) {

        public static Key of(DocumentContent document) {
            return new Key(document.path(), document.size(), document.lastModifiedMillis(),
                    textDigest(document.text()));
        }
    }

    static final int MAX_ENTRIES = 8;
    /** Tree nodes across cached trees (each tree is capped at 20k nodes). */
    static final long MAX_NODES = 100_000;

    public XmlTreeCache() {
        super("xml-tree", MAX_ENTRIES, MAX_NODES,
                (key, document) -> XmlTree.parse(document.text()),
                tree -> Math.max(1, tree.nodes()), ignored -> { });
    }
}
