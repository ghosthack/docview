package io.github.ghosthack.docview.textmate.internal;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.joni.Matcher;
import org.joni.Option;

/**
 * Hard per-line deadline for TextMate tokenizing. A single Joni regex search
 * cannot otherwise be stopped, and some grammar patterns are super-linear on
 * hostile input. Work run through {@link #within} has a deadline; every regex
 * search checks it on entry, and a watchdog thread interrupts the running
 * matcher (via {@link Matcher#interrupt()}, never a thread interrupt) once it
 * passes. Expiry surfaces as {@link Expired}.
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

    private final long endNanos;
    private volatile Matcher running;
    private volatile boolean expired;

    private LineDeadline(long endNanos) {
        this.endNanos = endNanos;
    }

    /** Runs {@code action} on this thread with a deadline of {@code limit}. */
    public static <T> T within(Duration limit, Supplier<T> action) {
        var deadline = new LineDeadline(System.nanoTime() + limit.toNanos());
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
        if (deadline.expired || System.nanoTime() >= deadline.endNanos) {
            deadline.expired = true;
            throw new Expired();
        }
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
                if (now < deadline.endNanos) continue;
                deadline.expired = true;
                Matcher matcher = deadline.running;
                if (matcher != null) matcher.interrupt();
            }
        }
    }
}
