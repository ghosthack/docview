package io.github.ghosthack.docview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HighlightBudgetTest {

    /** Reports a fixed tokenizing time and records each allowance it gets. */
    private static final class ReportingSession implements HighlightSession {
        final List<Long> allowances = new ArrayList<>();
        final long reports;

        ReportingSession(long reports) {
            this.reports = reports;
        }

        @Override
        public List<HighlightToken> tokensForLine(int lineIndex) {
            throw new AssertionError("the budget passes an allowance");
        }

        @Override
        public List<HighlightToken> tokensForLine(int lineIndex, long maxNanos,
                                                  LongConsumer spent) {
            allowances.add(maxNanos);
            spent.accept(reports);
            return List.of(new HighlightToken(0, 1, HighlightRole.KEYWORD));
        }
    }

    @Test
    void passesTheRemainingBudgetAndChargesReportedTime() {
        var budget = new HighlightBudget(1_000);
        var session = new ReportingSession(400);

        budget.tokens(session, 0, "x");
        budget.tokens(session, 1, "x");
        assertFalse(budget.exhausted());
        budget.tokens(session, 2, "x");

        assertEquals(List.of(1_000L, 600L, 200L), session.allowances);
        assertTrue(budget.exhausted());
        assertEquals(HighlightEngines.plainLine("x"), budget.tokens(session, 3, "x"));
        assertEquals(3, session.allowances.size(), "no call once spent");
    }

    @Test
    void sessionsWithoutAllowanceSupportAreChargedWallTime() {
        var budget = new HighlightBudget(1);
        HighlightSession slow = line -> {
            long until = System.nanoTime() + 2_000_000;
            while (System.nanoTime() < until) Thread.onSpinWait();
            return List.of(new HighlightToken(0, 1, HighlightRole.KEYWORD));
        };

        budget.tokens(slow, 0, "x");

        assertTrue(budget.exhausted());
    }
}
