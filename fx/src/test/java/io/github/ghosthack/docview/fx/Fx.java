package io.github.ghosthack.docview.fx;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs test code on the JavaFX thread; skips the test where no toolkit can start (no display). */
final class Fx {

    private static Boolean available;

    private Fx() {
    }

    private static synchronized boolean start() {
        if (available == null) {
            try {
                CountDownLatch started = new CountDownLatch(1);
                Platform.startup(started::countDown);
                available = started.await(10, TimeUnit.SECONDS);
                Platform.setImplicitExit(false);
            } catch (IllegalStateException alreadyStarted) {
                available = true;
            } catch (RuntimeException | Error | InterruptedException noToolkit) {
                available = false;
            }
        }
        return available;
    }

    /** Runs {@code action} on the FX thread and waits, rethrowing its failure. */
    static void run(Runnable action) {
        assumeTrue(start(), "JavaFX toolkit unavailable");
        var failure = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("FX thread timed out");
        } catch (InterruptedException interrupted) {
            throw new AssertionError(interrupted);
        }
        if (failure.get() instanceof RuntimeException e) throw e;
        if (failure.get() instanceof Error e) throw e;
    }
}
