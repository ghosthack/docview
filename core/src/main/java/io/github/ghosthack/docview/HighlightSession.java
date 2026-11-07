package io.github.ghosthack.docview;

import java.util.List;
import java.util.function.LongConsumer;

/**
 * Document-scoped highlighting state. Stateful engines may cache lexer
 * checkpoints here so virtualized rows can be requested out of order.
 */
public interface HighlightSession extends AutoCloseable {

    List<HighlightToken> tokensForLine(int lineIndex);

    /**
     * Like {@link #tokensForLine(int)}, within a time allowance: a session
     * that has to tokenize earlier lines first should stop spending once
     * {@code maxNanos} is used and return plain tokens for the rest. The time
     * the call actually spent tokenizing is reported to {@code spent}, which
     * lets an engine leave out one-time setup or waiting on other documents.
     *
     * <p>The default ignores the allowance and reports the call's wall time.
     */
    default List<HighlightToken> tokensForLine(int lineIndex, long maxNanos,
                                               LongConsumer spent) {
        long started = System.nanoTime();
        try {
            return tokensForLine(lineIndex);
        } finally {
            spent.accept(System.nanoTime() - started);
        }
    }

    @Override
    default void close() {
    }
}
