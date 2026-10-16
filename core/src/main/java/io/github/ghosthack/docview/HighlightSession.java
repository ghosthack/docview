package io.github.ghosthack.docview;

import java.util.List;

/**
 * Document-scoped highlighting state. Stateful engines may cache lexer
 * checkpoints here so virtualized rows can be requested out of order.
 */
public interface HighlightSession extends AutoCloseable {

    List<HighlightToken> tokensForLine(int lineIndex);

    @Override
    default void close() {
    }
}
