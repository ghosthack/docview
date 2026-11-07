package io.github.ghosthack.docview;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Total highlighting time one document may spend across all its code blocks.
 * Once spent, the remaining code lines render plain, so a document with many
 * hostile code blocks cannot keep a tokenizer busy indefinitely.
 */
final class HighlightBudget {

    /** Default per-document budget. */
    static final long DOCUMENT_NANOS = 1_500_000_000L;

    private final long limitNanos;
    private final AtomicLong spentNanos = new AtomicLong();

    HighlightBudget(long limitNanos) {
        this.limitNanos = limitNanos;
    }

    static HighlightBudget perDocument() {
        return new HighlightBudget(DOCUMENT_NANOS);
    }

    boolean exhausted() {
        return spentNanos.get() >= limitNanos;
    }

    /**
     * Tokens for one line, or plain tokens once the budget is spent. The
     * session gets the remaining budget as its allowance, so one call (which
     * may tokenize every earlier line of a block) cannot run far past it, and
     * is charged the tokenizing time it reports.
     */
    java.util.List<HighlightToken> tokens(HighlightSession session, int line, String text) {
        if (session == null) return HighlightEngines.plainLine(text);
        long remaining = limitNanos - spentNanos.get();
        if (remaining <= 0) return HighlightEngines.plainLine(text);
        return session.tokensForLine(line, remaining,
                spent -> spentNanos.addAndGet(Math.max(0, spent)));
    }
}
