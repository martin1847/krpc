package tech.krpc.client;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * C1 (HARDEN-B2): {@link SimpleLRUCache} must be safe under concurrent get/set.
 *
 * <p>The backing map is a {@code LinkedHashMap(accessOrder=true)}, so even {@code get()} is a
 * STRUCTURAL mutation — {@code afterNodeAccess} relinks the touched entry to the tail, writing
 * {@code head}/{@code tail} and neighbour {@code before}/{@code after} pointers. Because the key
 * space ({@value #KEY_SPACE}) dwarfs capacity ({@value #CACHE_CAPACITY}), almost every {@code set}
 * inserts a NEW key, so {@code afterNodeInsertion} evicts on nearly every put — driving continuous
 * {@code removeNode} bin-chain surgery and access-order relinking in parallel. Pre-fix, the
 * unsynchronized get/set raced on those pointers and corrupted them:
 * <ul>
 *   <li>{@link NullPointerException} out of {@code afterNodeAccess}/{@code removeNode}/{@code resize}
 *       when a neighbour or {@code next} pointer was nulled mid-surgery by another thread;</li>
 *   <li>lost evictions letting {@code size} grow past the resize threshold → a concurrent
 *       {@code resize()} that cross-links a bin into a cycle → a 100% CPU self-spin on a broken
 *       {@code next} pointer that never terminates.</li>
 * </ul>
 *
 * <p><b>Teeth.</b> Real platform threads (genuine preemptive parallelism), a PREFILLED cache (every
 * {@code get} hits and relinks from op #1), and a PRECOMPUTED shared key array so the hot loop is
 * almost entirely map operations — threads spend their time INSIDE get/set, maximising the chance
 * two are mutating the linked structure simultaneously (String-concat allocation per op previously
 * kept them outside the map and hid the race). {@value #THREADS} threads (oversubscribed on this
 * box → forced preemption) × {@value #OPS_PER_WAVE} ops × up to {@value #WAVES} waves drive tens of
 * millions of racing relink/evict/resize interleavings; the waves multiply reproduction probability
 * so a rare interleaving is caught reliably, not once-in-a-while. A {@link CountDownLatch} start
 * gate releases every thread simultaneously to peak contention.
 *
 * <p>Three independent contracts, all required:
 * <ol>
 *   <li>no worker thread threw (collected Throwables empty) — kills the NPE/corruption modes;</li>
 *   <li>the whole run finishes inside {@link #TIMEOUT} via {@code assertTimeoutPreemptively} —
 *       kills the never-terminating self-spin (a hung run trips the preemptive timeout rather than
 *       blocking the suite forever);</li>
 *   <li>after quiescence the LRU capacity invariant holds ({@code map.size() <= capacity}) — a
 *       grown map is direct evidence of lost, raced evictions.</li>
 * </ol>
 * Against the pre-{@code synchronized} code this reproduces (throws, hangs, or over-grows); against
 * the fix it completes cleanly, bounded, well under the timeout.
 */
class SimpleLRUCacheConcurrencyTest {

    private static final int CACHE_CAPACITY = 8;    // tiny table (~16 bins) → dense collisions + constant eviction
    private static final int KEY_SPACE = 512;       // 64× capacity → nearly every set inserts a NEW key
    private static final int THREADS = 32;          // >= 16 required; real platform threads, oversubscribed
    private static final int OPS_PER_WAVE = 200_000; // per thread per wave; >= 5_000 required
    private static final int WAVES = 5;             // fresh cache each wave; pre-fix reproduces on wave 0, extras are insurance
    private static final int EXPIRE_SECONDS = 60;   // positive → entries stay live during the run
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Shared, immutable key set so the hot loop is pure get/set (no per-op allocation). */
    private static final String[] KEYS = buildKeys();

    private static String[] buildKeys() {
        String[] keys = new String[KEY_SPACE];
        for (int i = 0; i < KEY_SPACE; i++) {
            keys[i] = "k" + i;
        }
        return keys;
    }

    @Test
    void concurrentGetSetNeitherCorruptsNorHangs() {
        assertTimeoutPreemptively(TIMEOUT, () -> {
            List<Throwable> failures = new CopyOnWriteArrayList<>();

            for (int wave = 0; wave < WAVES && failures.isEmpty(); wave++) {
                runOneWave(wave, failures);
            }

            assertTrue(failures.isEmpty(),
                    () -> "concurrent get/set must not corrupt the cache; captured "
                            + failures.size() + " failure(s): "
                            + failures.stream()
                                      .map(x -> x.getCause() == null ? x.toString()
                                              : x.getMessage() + " -> " + x.getCause())
                                      .collect(Collectors.joining("; ")));
        });
    }

    private static void runOneWave(int wave, List<Throwable> failures) throws InterruptedException {
        SimpleLRUCache cache = new SimpleLRUCache(CACHE_CAPACITY);
        byte[] payload = {1, 2, 3, 4, 5, 6, 7, 8};

        // Prefill so get() hits (and thus relinks under accessOrder) immediately.
        for (String k : KEYS) {
            cache.set(k, payload, EXPIRE_SECONDS);
        }

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(THREADS);
        List<Thread> threads = new ArrayList<>(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final long seed = 0x9E3779B97F4A7C15L ^ ((long) wave << 48) ^ ((long) t << 32) ^ t;
            Thread th = new Thread(() -> {
                java.util.Random rnd = new java.util.Random(seed); // deterministic; seed reported on failure
                ready.countDown();
                try {
                    startGate.await(); // fire together
                    for (int i = 0; i < OPS_PER_WAVE; i++) {
                        String key = KEYS[rnd.nextInt(KEY_SPACE)];
                        // Write-biased 3:1 — most ops are set() (new key → insert + evict surgery),
                        // interleaved with get() (accessOrder relink) so both structural paths race.
                        if ((i & 3) != 0) {
                            cache.set(key, payload, EXPIRE_SECONDS);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (Throwable ex) {
                    failures.add(new IllegalStateException("wave=" + wave + " seed=" + seed, ex));
                }
            }, "lru-hammer-" + wave + "-" + t);
            th.setDaemon(true);
            threads.add(th);
            th.start();
        }

        ready.await();         // all threads parked at the gate
        startGate.countDown(); // release the herd

        // Bounded join per thread; a hung (spinning) worker won't join and the outer
        // assertTimeoutPreemptively fires. Give each the remaining wall budget generously.
        for (Thread th : threads) {
            th.join(TIMEOUT.toMillis());
        }

        // Contract 3: after quiescence the LRU capacity invariant must hold. A map grown past
        // capacity is direct evidence evictions were lost to a race. (Safe: single-threaded now;
        // same package as the cache, so map is visible.)
        int size = cache.map.size();
        if (size > CACHE_CAPACITY) {
            failures.add(new IllegalStateException(
                    "wave=" + wave + " LRU capacity invariant broken: size=" + size
                            + " > capacity=" + CACHE_CAPACITY + " (evictions lost to a race)"));
        }
    }
}
