/**
 * KRPC-JWKS-001 — the CLAIM half of the off-thread HIT refresh, observed at three independent
 * points so a thundering herd cannot hide behind a single counter:
 *
 * <ul>
 *   <li><b>worker-start</b> — how many refresh workers were spawned ({@code startRefreshWorker});</li>
 *   <li><b>claim-success</b> — how many request threads won the window ({@code claimFetchAttempt});</li>
 *   <li><b>HTTP attempt</b> — how many requests actually reached the JWKS endpoint.</li>
 * </ul>
 *
 * <p>Asserting only the last one would accept "N virtual threads started, N claims raced, one
 * fetch survived" — which is a spawn storm, not single-flight. These observation points are
 * overrides of the production seams, so unlike {@code JwsVerifyAsyncRefreshTest} this file does NOT
 * compile against the pre-fix product; its evidence is mutation-based (see the IMPL note).
 */
package tech.krpc.server.jws;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import tech.krpc.server.ServerContext;

class JwsVerifyAsyncClaimTest {

    static final String JWKS_PATH = "/.well-known/jwks.json";
    static final long SETTLE_MILLIS = 400L;

    HttpServer jwksServer;
    ExecutorService jwksPool;
    volatile String currentJwks;
    final AtomicInteger httpAttempts = new AtomicInteger();
    volatile CountDownLatch fetchEntered;

    @AfterEach
    void tearDown() {
        if (jwksServer != null) {
            jwksServer.stop(0);
            jwksServer = null;
        }
        if (jwksPool != null) {
            jwksPool.shutdownNow();
            jwksPool = null;
        }
        ServerContext.regCredentialVerify(null);
    }

    /** Production JwsVerify with the claim and the spawn observed (both delegate to super). */
    static class ObservingJwsVerify extends JwsVerify {
        final AtomicInteger gatedClaims = new AtomicInteger();
        final AtomicInteger workerStarts = new AtomicInteger();
        /** Counted down every time a refresh worker RETURNS (fetched or dropped). */
        final CountDownLatch workerExited = new CountDownLatch(1);
        volatile boolean failSpawn;
        /**
         * When set, a WINNING gated claim parks here after the real CAS has already happened — the
         * only way to place another commit deterministically inside the claim window and see which
         * side of the CAS the generation baseline was read on.
         */
        volatile CountDownLatch pauseAfterClaimCas;

        ObservingJwsVerify(String url) {
            super(url);
        }

        @Override
        long claimFetchAttempt(boolean gated) {
            long claimedAt = super.claimFetchAttempt(gated);
            if (gated && claimedAt != 0L) {
                gatedClaims.incrementAndGet();
                CountDownLatch pause = pauseAfterClaimCas;
                if (pause != null) {
                    try {
                        pause.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return claimedAt;
        }

        @Override
        void startRefreshWorker(long claimedGeneration) {
            workerStarts.incrementAndGet();
            if (failSpawn) {
                throw new IllegalStateException("simulated refresh-worker start failure");
            }
            super.startRefreshWorker(claimedGeneration);
        }

        @Override
        void refreshClaimed(long claimedGeneration) {
            try {
                super.refreshClaimed(claimedGeneration);
            } finally {
                workerExited.countDown();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // R2 BLOCKER regression: a commit that lands INSIDE the claim window (after the claim CAS, while
    // the request thread has not yet spawned its worker) must make the worker drop its attempt. That
    // holds only if the generation baseline is read BEFORE the CAS; reading it after swallows such a
    // commit into the baseline, and the worker fetches a second time — publishing an older endpoint
    // view after a newer commit. Formalised from the reviewer's interleaving of the same name.
    // ------------------------------------------------------------------------------------------
    @Test
    void claimThenCommitBeforeGenerationCaptureMustDropWorker() throws Exception {
        Kp k1 = genKey("K1");
        Kp k2 = genKey("K2");
        startJwks(jwksDoc(k1));
        ObservingJwsVerify verify = new ObservingJwsVerify(jwksUrl());
        verify.loadJwks();
        httpAttempts.set(0);
        verify.gatedClaims.set(0);
        verify.workerStarts.set(0);

        CountDownLatch release = new CountDownLatch(1);
        verify.pauseAfterClaimCas = release;
        ageWindow(verify);
        Thread hit = new Thread(() -> verify.useKey("K1"), "hit-caller");
        hit.start();

        // POSITIVE HANDSHAKE: the claim CAS really happened and the request thread is parked in the
        // claim window — the commit below is therefore genuinely a POST-CLAIM commit.
        awaitClaims(verify, 1);
        assertEquals(0, verify.workerStarts.get(), "the worker must not be spawned yet");

        // A newer keyset commits while the claimer sits in that window...
        currentJwks = jwksDoc(k1, k2);
        verify.loadJwks();
        assertEquals(Set.of("K1", "K2"), verify.jwksCache.keySet(), "the newer load must commit");
        // ...and the endpoint then serves the OLDER document again: a redundant refetch is visible
        // both as a second attempt and as the newer keyset being replaced by the older view.
        currentJwks = jwksDoc(k1);

        verify.pauseAfterClaimCas = null;
        release.countDown();
        hit.join(15_000);
        assertEquals(1, verify.workerStarts.get(), "exactly one worker for one claim");
        assertTrue(verify.workerExited.await(15, TimeUnit.SECONDS),
                "the refresh worker never finished — the assertions below would be vacuous");

        assertEquals(1, httpAttempts.get(),
                "a commit that landed INSIDE the claim window did not stop the worker: the generation "
                        + "baseline is being read AFTER the claim CAS, so that commit was swallowed");
        assertEquals(Set.of("K1", "K2"), verify.jwksCache.keySet(),
                "the older endpoint view was published over the newer committed keyset");
    }

    // ------------------------------------------------------------------------------------------
    // Thundering herd: N concurrent stale HITs ⇒ ONE claim, ONE worker, ONE HTTP attempt, and all N
    // requests served from the old keyset without waiting.
    //
    // The burst is released by a SPIN GATE (a volatile flag the threads busy-wait on), not by a
    // barrier/latch: a barrier wakes its parties one by one through the scheduler, which serialises
    // them enough for a non-atomic check-then-write claim to look correct. Spinning threads see the
    // flag within nanoseconds of each other. The burst is repeated over many rounds so a claim that
    // is only *usually* exclusive cannot pass.
    // ------------------------------------------------------------------------------------------
    @Test
    void nConcurrentHitsSpawnExactlyOneAttempt() throws Exception {
        final int n = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
        final int rounds = 40;
        Kp k1 = genKey("K1");
        startJwks(jwksDoc(k1));
        ObservingJwsVerify verify = new ObservingJwsVerify(jwksUrl());
        verify.loadJwks();
        // Bootstrap is excluded from the counts BY CONSTRUCTION (zeroed here, not assumed away).
        httpAttempts.set(0);
        verify.gatedClaims.set(0);
        verify.workerStarts.set(0);

        var served = new AtomicInteger();
        var failures = new CopyOnWriteArrayList<Throwable>();

        for (int round = 1; round <= rounds; round++) {
            final int r = round;
            var go = new AtomicBoolean(false);
            var ready = new CountDownLatch(n);
            var done = new CountDownLatch(n);
            long aged = ageWindow(verify);
            for (int i = 0; i < n; i++) {
                new Thread(() -> {
                    try {
                        ready.countDown();
                        while (!go.get()) {
                            Thread.onSpinWait();
                        }
                        if (verify.useKey("K1") != null) {
                            served.incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        failures.add(e);
                    } finally {
                        done.countDown();
                    }
                }, "hit-" + r + "-" + i).start();
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "HIT threads did not reach the spin gate");
            go.set(true);

            assertTrue(done.await(10, TimeUnit.SECONDS),
                    () -> "round " + r + ": not every concurrent HIT returned — a request thread "
                            + "waited on JWKS I/O");
            assertTrue(failures.isEmpty(), () -> "HIT threads failed: " + failures);

            // POSITIVE HANDSHAKE for this round: the burst really did produce one committed refresh.
            awaitCommit(verify, aged);

            assertTrue(verify.workerStarts.get() <= n * r,
                    () -> "impossible: more workers than requests (" + verify.workerStarts.get() + ")");
            assertEquals(r, verify.workerStarts.get(),
                    () -> "round " + r + ": N=" + n + " concurrent stale HITs have spawned "
                            + verify.workerStarts.get() + " refresh workers in total (expected one per"
                            + " round): the window must be CLAIMED BEFORE the spawn, not inside the"
                            + " worker");
            assertEquals(r, verify.gatedClaims.get(),
                    () -> "round " + r + ": " + verify.gatedClaims.get() + " claims taken in total "
                            + "(expected one per round): the claim must be a single atomic CAS, not a "
                            + "volatile check-then-write");
            assertEquals(r, httpAttempts.get(),
                    () -> "round " + r + ": " + httpAttempts.get() + " JWKS requests in total, "
                            + "expected one per window");
        }

        assertEquals(n * rounds, served.get(), "every concurrent HIT must be served from the keyset");
        assertEquals(rounds, httpAttempts.get(), "one window = one attempt, across every round");
    }

    // ------------------------------------------------------------------------------------------
    // Interleaving cell 2: the claim is NOT rolled back when the worker fails to start. The window
    // is spent (next window retries), the request is unaffected, and exactly one line is logged —
    // rolling back here would turn a spawn failure into a spawn/claim storm at the JWKS origin.
    // ------------------------------------------------------------------------------------------
    @Test
    void refreshWorkerStartFailureSpendsTheWindowWithoutRollback() throws Exception {
        Kp k1 = genKey("K1");
        startJwks(jwksDoc(k1));
        ObservingJwsVerify verify = new ObservingJwsVerify(jwksUrl());
        verify.loadJwks();
        httpAttempts.set(0);
        verify.gatedClaims.set(0);
        verify.workerStarts.set(0);
        verify.failSpawn = true;

        ListAppender<ILoggingEvent> logs = captureJwsLog();
        try {
            long aged = ageWindow(verify);

            assertNotNull(verify.useKey("K1"),
                    "a failed refresh spawn must not fail an otherwise-valid request");

            long claimed = verify.lastTryFetch;
            assertTrue(claimed > aged,
                    "the claim was ROLLED BACK after the spawn failure — the window must be spent");
            assertEquals(1, verify.workerStarts.get(), "exactly one spawn was attempted");
            assertEquals(1, verify.gatedClaims.get(), "exactly one claim was taken");
            assertEquals(0, httpAttempts.get(), "no fetch can have happened: the worker never ran");

            // The spent window holds: a following burst must NOT re-claim or re-spawn.
            for (int i = 0; i < 5; i++) {
                assertNotNull(verify.useKey("K1"), "HITs keep being served");
            }
            assertEquals(1, verify.workerStarts.get(),
                    "a spawn failure turned into a spawn storm inside one window");
            assertEquals(1, verify.gatedClaims.get(), "the window was re-claimed inside GAP_MILL");
            assertEquals(claimed, verify.lastTryFetch, "lastTryFetch moved inside the window");

            List<String> lines = logs.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN || e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("jwks"))
                    .toList();
            assertEquals(1, lines.size(),
                    () -> "a spawn failure must log EXACTLY ONE line, got " + lines);
        } finally {
            detachJwsLog(logs);
        }

        // Recovery: the NEXT window really does fetch — the failure spent one window, not the path.
        verify.failSpawn = false;
        CountDownLatch entered = armEntered();
        ageWindow(verify);
        assertNotNull(verify.useKey("K1"), "HIT still served");
        assertTrue(entered.await(10, TimeUnit.SECONDS),
                "after a spawn failure the next window never refreshed (path permanently broken)");
        awaitAttempts(1);
    }

    // ==========================================================================================
    // Harness (kept minimal; the full JWT matrix lives in JwsVerifyHardenTest)
    // ==========================================================================================

    /** Both clocks aged: "no fetch for more than one window" (see JwsVerifyAsyncRefreshTest). */
    private static long ageWindow(JwsVerify v) {
        long aged = System.currentTimeMillis() - JwsVerify.GAP_MILL - 1_000L;
        v.lastOkFetch = aged;
        v.lastTryFetch = aged;
        return aged;
    }

    private static void awaitCommit(JwsVerify v, long okBefore) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (v.lastOkFetch <= okBefore) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the refresh never committed (lastOkFetch still " + v.lastOkFetch + ")");
            }
            Thread.sleep(10);
        }
    }

    private CountDownLatch armEntered() {
        CountDownLatch latch = new CountDownLatch(1);
        fetchEntered = latch;
        return latch;
    }

    private static void awaitClaims(ObservingJwsVerify v, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (v.gatedClaims.get() < expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("expected " + expected + " gated claims, saw " + v.gatedClaims.get());
            }
            Thread.sleep(5);
        }
    }

    private void awaitAttempts(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (httpAttempts.get() < expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("expected " + expected + " JWKS attempts, saw " + httpAttempts.get());
            }
            Thread.sleep(10);
        }
    }

    private static ListAppender<ILoggingEvent> captureJwsLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(JwsVerify.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachJwsLog(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(JwsVerify.class)).detachAppender(appender);
    }

    record Kp(String kid, String priB64, ECPublicKey pub) {}

    private static Kp genKey(String kid) throws Exception {
        var kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        String priB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(kp.getPrivate().getEncoded());
        return new Kp(kid, priB64, (ECPublicKey) kp.getPublic());
    }

    private static String jwksDoc(Kp... keys) {
        var sb = new StringBuilder("{\"keys\":[");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            var w = keys[i].pub.getW();
            sb.append("{\"kty\":\"EC\",\"use\":\"sig\",\"crv\":\"P-256\",\"kid\":\"").append(keys[i].kid)
                    .append("\",\"x\":\"").append(coord(w.getAffineX()))
                    .append("\",\"y\":\"").append(coord(w.getAffineY())).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private static String coord(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] fixed = new byte[32];
        if (raw.length == 33 && raw[0] == 0) {
            System.arraycopy(raw, 1, fixed, 0, 32);
        } else if (raw.length <= 32) {
            System.arraycopy(raw, 0, fixed, 32 - raw.length, raw.length);
        } else {
            throw new IllegalStateException("unexpected coord length " + raw.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
    }

    private void startJwks(String body) throws Exception {
        currentJwks = body;
        jwksPool = Executors.newCachedThreadPool();
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.setExecutor(jwksPool);
        jwksServer.createContext(JWKS_PATH, ex -> {
            httpAttempts.incrementAndGet();
            byte[] out = currentJwks.getBytes(UTF_8);
            CountDownLatch entered = fetchEntered;
            if (entered != null) {
                entered.countDown();
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        jwksServer.start();
    }

    private String jwksUrl() {
        return "http://127.0.0.1:" + jwksServer.getAddress().getPort() + JWKS_PATH;
    }
}
