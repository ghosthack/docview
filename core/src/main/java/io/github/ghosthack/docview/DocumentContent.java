package io.github.ghosthack.docview;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/** Immutable decoded document plus a line index used by navigation and search. */
public record DocumentContent(
        Path path,
        DocumentKind kind,
        String text,
        Charset charset,
        long size,
        long lastModifiedMillis,
        int[] lineStarts
) {
    public DocumentContent {
        lineStarts = Arrays.copyOf(lineStarts, lineStarts.length);
    }

    /** Value equality: the line index is compared by content, not identity. */
    @Override
    public boolean equals(Object other) {
        return other instanceof DocumentContent that
                && size == that.size && lastModifiedMillis == that.lastModifiedMillis
                && path.equals(that.path) && kind == that.kind
                && text.equals(that.text) && charset.equals(that.charset)
                && Arrays.equals(lineStarts, that.lineStarts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, kind, text, charset, size, lastModifiedMillis)
                * 31 + Arrays.hashCode(lineStarts);
    }

    @Override
    public String toString() {
        return "DocumentContent[path=" + path + ", kind=" + kind + ", size=" + size
                + ", lines=" + lineStarts.length + "]";
    }

    /**
     * In-memory content for {@code text}: line offsets are computed, size is
     * the UTF-8 length and the modification time is 0. {@code path} names the
     * document (links, cache keys); it is never read.
     */
    public static DocumentContent of(Path path, DocumentKind kind, String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        int[] starts = new int[count];
        for (int i = 0, line = 1; i < text.length(); i++) {
            if (text.charAt(i) == '\n') starts[line++] = i + 1;
        }
        return new DocumentContent(path.toAbsolutePath().normalize(), kind, text,
                StandardCharsets.UTF_8, text.getBytes(StandardCharsets.UTF_8).length,
                0L, starts);
    }

    @Override
    public int[] lineStarts() {
        return Arrays.copyOf(lineStarts, lineStarts.length);
    }

    public int lineCount() {
        return lineStarts.length;
    }

    public int lineOfOffset(int offset) {
        int clamped = Math.max(0, Math.min(text.length(), offset));
        int found = Arrays.binarySearch(lineStarts, clamped);
        return found >= 0 ? found : Math.max(0, -found - 2);
    }

    public int lineStart(int line) {
        return lineStarts[Math.max(0, Math.min(lineStarts.length - 1, line))];
    }

    public int lineEnd(int line) {
        int normalized = Math.max(0, Math.min(lineStarts.length - 1, line));
        int end = normalized + 1 < lineStarts.length
                ? lineStarts[normalized + 1] - 1 : text.length();
        if (end > 0 && text.charAt(end - 1) == '\r') end--;
        return end;
    }
}
