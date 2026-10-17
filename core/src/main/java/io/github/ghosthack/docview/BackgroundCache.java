package io.github.ghosthack.docview;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/**
 * Background builder and small weighted LRU of values built from a document
 * (WebView pages, block models, XML trees). Ask for the current document with
 * {@link #request} and for likely next documents with {@link #prefetch};
 * builds run on background threads.
 *
 * <p>Current requests run on their own thread so they never queue behind a
 * neighbour build; a request for a value that is already being built joins
 * that build. Prefetches run one at a time at minimum priority and are skipped
 * once {@link #newGeneration} marks them stale (the user has moved on).
 * Evicted values are handed to the {@code evicted} callback.
 *
 * <p>After {@link #close} every pending and later future is cancelled, and a
 * build that was already running hands its value to {@code evicted}.
 */
public abstract sealed class BackgroundCache<K, V> implements AutoCloseable
        permits PageCache, BlockCache, XmlTreeCache {

    protected BackgroundCache(String name, int maxEntries, long maxWeight,
                              BiFunction<K, DocumentContent, V> builder,
                              ToLongFunction<V> weigher, Consumer<V> evicted) {
        this.current = Executors.newSingleThreadExecutor(
                daemon(name, Thread.NORM_PRIORITY));
        this.prefetch = Executors.newSingleThreadExecutor(
                daemon(name + "-prefetch", Thread.MIN_PRIORITY));
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
        this.builder = builder;
        this.weigher = weigher;
        this.evicted = evicted;
    }

    private final ExecutorService current;
    private final ExecutorService prefetch;
    private final int maxEntries;
    private final long maxWeight;
    private final BiFunction<K, DocumentContent, V> builder;
    private final ToLongFunction<V> weigher;
    private final Consumer<V> evicted;
    private final LinkedHashMap<K, V> cache =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Map<K, CompletableFuture<V>> building = new HashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private long cachedWeight;
    private boolean closed;

    /**
     * Returns the value for {@code key}: already completed when cached,
     * otherwise a future completed by a background build.
     */
    public CompletableFuture<V> request(K key, DocumentContent document) {
        CompletableFuture<V> future;
        synchronized (this) {
            if (closed) return cancelled();
            V cached = cache.get(key);
            if (cached != null) return CompletableFuture.completedFuture(cached);
            CompletableFuture<V> inFlight = building.get(key);
            if (inFlight != null) return inFlight;
            future = new CompletableFuture<>();
            building.put(key, future);
        }
        try {
            current.execute(() -> build(key, document, future));
        } catch (RejectedExecutionException closing) {
            synchronized (this) {
                building.remove(key, future);
            }
            future.cancel(false);
        }
        return future;
    }

    /**
     * Builds {@code key} in the background unless it is cached or building.
     * The future yields the value, or {@code null} if the prefetch went stale
     * (the user navigated on) before it started.
     */
    public CompletableFuture<V> prefetch(K key, DocumentContent document) {
        long scheduled = generation.get();
        synchronized (this) {
            if (closed) return cancelled();
            V cached = cache.get(key);
            if (cached != null) return CompletableFuture.completedFuture(cached);
            CompletableFuture<V> inFlight = building.get(key);
            if (inFlight != null) return inFlight;
        }
        var result = new CompletableFuture<V>();
        Runnable task = () -> {
            if (scheduled != generation.get()) {
                result.complete(null);
                return;
            }
            CompletableFuture<V> future;
            boolean owner = false;
            synchronized (this) {
                if (closed) {
                    result.cancel(false);
                    return;
                }
                V cached = cache.get(key);
                future = cached != null ? CompletableFuture.completedFuture(cached)
                        : building.get(key);
                if (future == null) {
                    future = new CompletableFuture<>();
                    building.put(key, future);
                    owner = true;
                }
            }
            future.whenComplete((value, error) -> forward(value, error, result));
            if (owner) build(key, document, future);
        };
        try {
            prefetch.execute(new PrefetchTask(task, result));
        } catch (RejectedExecutionException closing) {
            result.cancel(false);
        }
        return result;
    }

    /** A queued prefetch, so {@link #close} can cancel the ones it drops. */
    private record PrefetchTask(Runnable task, CompletableFuture<?> result)
            implements Runnable {
        @Override public void run() {
            task.run();
        }
    }

    private static <V> CompletableFuture<V> cancelled() {
        var future = new CompletableFuture<V>();
        future.cancel(false);
        return future;
    }

    private static <V> void forward(V value, Throwable error,
                                CompletableFuture<V> result) {
        if (error != null) result.completeExceptionally(error);
        else result.complete(value);
    }

    /** Marks queued prefetches stale; call it whenever the current document changes. */
    public void newGeneration() {
        generation.incrementAndGet();
    }

    public synchronized boolean isCached(K key) {
        return cache.containsKey(key);
    }

    private void build(K key, DocumentContent document,
                       CompletableFuture<V> future) {
        boolean wasClosed;
        try {
            V value = builder.apply(key, document);
            synchronized (this) {
                // A build that outlived close() must not refill the cache.
                wasClosed = closed;
                if (wasClosed) evicted.accept(value);
                else store(key, value);
                building.remove(key, future);
            }
            // Never hand out a value that was already passed to evicted.
            if (wasClosed) future.cancel(false);
            else future.complete(value);
        } catch (Throwable error) {
            synchronized (this) {
                wasClosed = closed;
                building.remove(key, future);
            }
            if (wasClosed) future.cancel(false);
            else future.completeExceptionally(error);
        }
    }

    private void store(K key, V value) {
        V previous = cache.put(key, value);
        if (previous != null) {
            cachedWeight -= weigher.applyAsLong(previous);
            if (previous != value) evicted.accept(previous);
        }
        cachedWeight += weigher.applyAsLong(value);
        Iterator<Map.Entry<K, V>> eldest = cache.entrySet().iterator();
        // Always keep the newest value, even one heavier than the budget.
        while (cache.size() > 1
                && (cache.size() > maxEntries || cachedWeight > maxWeight)
                && eldest.hasNext()) {
            Map.Entry<K, V> entry = eldest.next();
            if (entry.getKey().equals(key)) continue;
            cachedWeight -= weigher.applyAsLong(entry.getValue());
            eldest.remove();
            evicted.accept(entry.getValue());
        }
    }

    @Override
    public void close() {
        List<CompletableFuture<V>> pending;
        synchronized (this) {
            closed = true;
            pending = List.copyOf(building.values());
            building.clear();
            cache.values().forEach(evicted);
            cache.clear();
            cachedWeight = 0;
        }
        current.shutdownNow();
        for (Runnable dropped : prefetch.shutdownNow()) {
            if (dropped instanceof PrefetchTask task) task.result().cancel(false);
        }
        // Dropped and running builds never complete these on their own.
        pending.forEach(future -> future.cancel(false));
    }

    /**
     * SHA-256 of {@code text}'s UTF-16 code units as hex, for cache keys: equal
     * for equal text without the key retaining the text itself.
     */
    static String textDigest(String text) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
        byte[] chunk = new byte[16 * 1024];
        for (int start = 0; start < text.length(); ) {
            int end = Math.min(text.length(), start + chunk.length / 2);
            for (int i = start, j = 0; i < end; i++) {
                char c = text.charAt(i);
                chunk[j++] = (byte) (c >>> 8);
                chunk[j++] = (byte) c;
            }
            digest.update(chunk, 0, (end - start) * 2);
            start = end;
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static java.util.concurrent.ThreadFactory daemon(String name, int priority) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        };
    }
}
