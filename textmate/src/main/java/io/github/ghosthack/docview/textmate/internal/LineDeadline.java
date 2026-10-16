package io.github.ghosthack.docview.textmate.internal;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.joni.Matcher;
import org.joni.Option;

/**
 * Hard per-line limit on regex search time for TextMate tokenizing. A single
 * Joni search cannot otherwise be stopped, and some grammar patterns are
 * super-linear on hostile input. Within {@link #within}, only time spent
 * inside regex searches counts, so one-time grammar compilation on a cold
 * start never uses up a line's limit. Every search checks the accumulated
 * time on entry, and a watchdog thread interrupts a search that runs past it
 * (via {@link Matcher#interrupt()}, never a thread interrupt). Expiry
 * surfaces as {@link Expired}.
 *
 * <p>Internal to docview-textmate; not API.
 */
public final class LineDeadline {

    /** Thrown when the current deadline has passed. */
    public static final class Expired extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Expired() {
            super("TextMate line deadline expired", null, false, false);
        }
    }

    private static final long WATCHDOG_TICK_MILLIS = 5;
    private static final ThreadLocal<LineDeadline> CURRENT = new ThreadLocal<>();
    private static final Set<LineDeadline> ACTIVE = ConcurrentHashMap.newKeySet();
    private static volatile Thread watchdog;

    private final long limitNanos;
    /** Search time already used on this line; written by the owning thread. */
    private volatile long searchedNanos;
    /** Start of the search in progress, 0 when none. */
    private volatile long runningSince;
    private volatile Matcher running;
    private volatile boolean expired;

    private LineDeadline(long limitNanos) {
        this.limitNanos = limitNanos;
    }

    /** Runs {@code action} on this thread with a deadline of {@code limit}. */
    public static <T> T within(Duration limit, Supplier<T> action) {
        var deadline = new LineDeadline(limit.toNanos());
        LineDeadline outer = CURRENT.get();
        CURRENT.set(deadline);
        ACTIVE.add(deadline);
        ensureWatchdog();
        try {
            return action.get();
        } finally {
            ACTIVE.remove(deadline);
            if (outer == null) CURRENT.remove();
            else CURRENT.set(outer);
        }
    }

    /** Searches like {@link Matcher#search}, honoring the current deadline if any. */
    public static int search(Matcher matcher, int start, int end) {
        LineDeadline deadline = CURRENT.get();
        if (deadline == null) return matcher.search(start, end, Option.DEFAULT);
        if (deadline.expired || deadline.searchedNanos >= deadline.limitNanos) {
            deadline.expired = true;
            throw new Expired();
        }
        long started = System.nanoTime();
        deadline.runningSince = started;
        deadline.running = matcher;
        try {
            // Re-check after publishing the matcher: the watchdog sets
            // expired before reading running, so one side always sees the other.
            if (deadline.expired) throw new Expired();
            return matcher.searchInterruptible(start, end, Option.DEFAULT);
        } catch (InterruptedException interrupted) {
            throw new Expired();
        } finally {
            deadline.running = null;
            deadline.runningSince = 0;
            deadline.searchedNanos += System.nanoTime() - started;
        }
    }

    private static void ensureWatchdog() {
        if (watchdog != null) return;
        synchronized (LineDeadline.class) {
            if (watchdog != null) return;
            var thread = new Thread(LineDeadline::watch, "docview-textmate-deadline");
            thread.setDaemon(true);
            thread.start();
            watchdog = thread;
        }
    }

    private static void watch() {
        while (true) {
            try {
                Thread.sleep(WATCHDOG_TICK_MILLIS);
            } catch (InterruptedException ignored) {
                // Keep watching; this daemon thread ends with the JVM.
            }
            long now = System.nanoTime();
            for (LineDeadline deadline : ACTIVE) {
                Matcher matcher = deadline.running;
                long since = deadline.runningSince;
                if (matcher == null || since == 0) continue;
                if (deadline.searchedNanos + (now - since) < deadline.limitNanos) continue;
                deadline.expired = true;
                matcher.interrupt();
            }
        }
    }
}
