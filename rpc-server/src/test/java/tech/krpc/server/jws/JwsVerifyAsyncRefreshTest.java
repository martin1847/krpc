/**
 * KRPC-JWKS-001 — the HIT-path JWKS refresh must never run on the request thread
 * (stale-while-revalidate), while every HARDEN-B1 property it inherits stays intact.
 *
 * <p>Every test here is written against the EXISTING package-private surface only
 * ({@code useKey}, {@code maybeRefetch}, {@code loadJwks}, {@code fetchLock}, {@code lastTryFetch},
 * {@code lastOkFetch}, {@code jwksCache}) so THIS FILE COMPILES AND RUNS UNCHANGED AGAINST THE
 * PRE-FIX PRODUCT — that is what makes the red/green evidence for
 * {@link #hitPastWindowReturnsWithoutWaitingForFetch()} product evidence rather than a change of
 * test rig. The claim/spawn observation points that only exist after the fix live in
 * {@code JwsVerifyAsyncClaimTest}.
 *
 * <p>Observability protocol (no assertion may pass because nothing happened):
 * <ul>
 *   <li>the loopback JWKS endpoint counts every request ({@code httpAttempts}) and tracks the
 *       maximum number of CONCURRENT requests ({@code maxInFlight}) — it runs on a real thread
 *       pool, so two concurrent fetches would genuinely overlap;</li>
 *   <li>a fetch can be parked inside the endpoint ({@code gate}) and announces its arrival
 *       ({@code fetchEntered}) — the positive handshake that a refresh really started;</li>
 *   <li>the endpoint captures the document to serve AT REQUEST ENTRY, so a parked (older) fetch
 *       serves the older keyset even after the test publishes a newer one.</li>
 * </ul>
 *
 * <p>Package is {@code tech.krpc.server.jws} on purpose (package-private access). The JWT/JWKS
 * harness is inlined, matching {@code JwsVerifyHardenTest}.
 */
package tech.krpc.server.jws;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.grpc.StatusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import tech.krpc.server.ServerContext;

class JwsVerifyAsyncRefreshTest {

    static final String JWKS_PATH = "/.well-known/jwks.json";
    static final String CID = "cid";
    /** How long a negative assertion ("no further fetch happened") is given to be falsified. */
    static final long SETTLE_MILLIS = 400L;

    HttpServer jwksServer;
    ExecutorService jwksPool;
    volatile String currentJwks;
    volatile int jwksStatus = 200;

    final AtomicInteger httpAttempts = new AtomicInteger();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxInFlight = new AtomicInteger();
    /** Non-null ⇒ every request entering the endpoint parks on it before responding. */
    volatile CountDownLatch gate;
    /** Non-null ⇒ counted down as soon as a request ENTERS the endpoint (start handshake). */
    volatile CountDownLatch fetchEntered;
    final List<CountDownLatch> armedGates = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        gate = null;
        armedGates.forEach(CountDownLatch::countDown); // never leave a handler parked
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

    // ------------------------------------------------------------------------------------------
    // 1. THE POINT OF THE CHANGE: a HIT past the refresh window returns while the refresh is still
    //    in flight. Pre-fix this test is RED (the request thread sits inside the JWKS fetch).
    // ------------------------------------------------------------------------------------------
    @Test
    void hitPastWindowReturnsWithoutWaitingForFetch() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        httpAttempts.set(0);

        CountDownLatch release = armGate();
        CountDownLatch entered = armEntered();
        long okBefore = ageWindow(verify);

        var returned = new AtomicReference<ECPublicKey>();
        CountDownLatch done = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            returned.set(verify.useKey("K1"));
            done.countDown();
        }, "hit-caller");
        caller.start();

        // (a) POSITIVE HANDSHAKE — the stale HIT really did trigger a fetch, and that fetch is now
        //     parked inside the endpoint. Without this the timing assertion below could pass
        //     because no refresh happened at all.
        assertTrue(entered.await(10, TimeUnit.SECONDS),
                "the stale HIT triggered no JWKS fetch at all — nothing arrived at the endpoint");

        // (b) THE PROPERTY — the request thread is already done while that fetch is still blocked.
        assertTrue(done.await(3, TimeUnit.SECONDS),
                "useKey() did not return while the JWKS fetch was still parked: the request thread "
                        + "waited on JWKS I/O (pre-fix behaviour)");
        assertNotNull(returned.get(),
                "a still-known kid must keep being served from the last-known-good keyset");
        assertEquals(okBefore, verify.lastOkFetch,
                "the refresh must still be IN FLIGHT (nothing committed) when the request returned");
        assertEquals(1, httpAttempts.get(), "exactly one refresh attempt for one stale window");

        // (c) and the refresh really completes afterwards — the fetch is deferred, not dropped.
        gate = null;
        release.countDown();
        awaitCommit(verify, okBefore);
        caller.join(5_000);
        assertEquals(1, httpAttempts.get(), "the released refresh must not fetch a second time");
        assertTrue(verify.isReady(), "a completed refresh keeps the verifier ready");
    }

    // ------------------------------------------------------------------------------------------
    // 2. Clock invariant, part 1: the number of WINDOW-GATED request-path attempts (HIT + MISS,
    //    excluding bootstrap loadJwks and the background retry — the 6th interleaving cell's
    //    counting rule) is exactly what it was before the change.
    // ------------------------------------------------------------------------------------------
    @Test
    void windowGatedAttemptCountUnchanged() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        String tokenK1 = jwt(k1, validClaims("u"));
        httpAttempts.set(0); // bootstrap load is excluded BY CONSTRUCTION, not by hoping

        // (a) window fresh: a mixed HIT/MISS burst must not fetch at all.
        verify.lastTryFetch = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            assertNotNull(verify.useKey("K1"), "a HIT inside the window keeps serving");
            assertNull(verify.useKey("nope-" + i), "an unknown kid inside the window stays unknown");
        }
        assertEquals(0, httpAttempts.get(), "inside the window NEITHER path may fetch");

        // (b) one stale window + a burst: exactly ONE attempt, no matter how many requests land.
        //     No gate here on purpose: the commit itself is the positive proof that the stale HIT
        //     produced an attempt, and it keeps this count comparable to the pre-fix product (whose
        //     HIT path fetches on the calling thread).
        long okBefore = ageWindow(verify);
        assertNotNull(verify.useKey("K1"), "a stale HIT still serves a key");
        awaitCommit(verify, okBefore);
        for (int i = 0; i < 5; i++) {
            assertNotNull(verify.useKey("K1"), "HIT inside the freshly claimed window");
            assertNull(verify.useKey("nope2-" + i), "unknown kid, window just claimed");
        }
        assertEquals(1, httpAttempts.get(),
                "one stale window = exactly ONE gated attempt regardless of the burst size");

        // (c) cross the window again with a MISS: the synchronous path attempts. Total 2.
        ageWindow(verify);
        assertNull(verify.useKey("still-unknown"), "an unknown kid stays unknown after the refetch");
        assertEquals(2, httpAttempts.get(),
                "a MISS outside the window must still fetch (rotation pickup not starved)");

        // Known-positive: this sample really did exercise fetches, so the counts above are not 0=0.
        assertTrue(httpAttempts.get() > 0, "no real attempt happened — the assertions above are vacuous");
        assertVerifyOk(verify, tokenK1, "the keyset is intact at the end of the mixed sample");
    }

    // ------------------------------------------------------------------------------------------
    // 3. Clock invariant, part 2: ONE attempt advances lastTryFetch EXACTLY ONCE, at claim time,
    //    before its I/O. A second advance point (e.g. re-stamping inside doFetch) is visible here
    //    because the attempt's I/O is deliberately delayed well past its claim.
    // ------------------------------------------------------------------------------------------
    @Test
    void oneAttemptAdvancesTheClockExactlyOnce() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        httpAttempts.set(0);
        long okBefore;

        CountDownLatch entered = armEntered();
        // Hold the fetch lock so the claimed attempt cannot reach its I/O for ~600ms: a stamp taken
        // at I/O time would differ from the claim stamp by that much.
        verify.fetchLock.lock();
        long stampAtClaim;
        try {
            okBefore = ageWindow(verify);
            Thread caller = new Thread(() -> verify.useKey("K1"), "hit-caller");
            caller.start();
            caller.join(5_000);
            stampAtClaim = verify.lastTryFetch;
            assertTrue(System.currentTimeMillis() - stampAtClaim < 5_000L,
                    "the window was never claimed before the I/O — nothing stamped the clock");
            Thread.sleep(600);
        } finally {
            verify.fetchLock.unlock();
        }

        assertTrue(entered.await(10, TimeUnit.SECONDS),
                "the claimed attempt never reached the endpoint after the lock was released");
        awaitCommit(verify, okBefore);
        assertEquals(stampAtClaim, verify.lastTryFetch,
                "lastTryFetch advanced a SECOND time for ONE attempt (second advance point/timestamp: "
                        + "claim and I/O must not both stamp the window clock)");
        assertEquals(1, httpAttempts.get(), "exactly one attempt for one claim");
    }

    // ------------------------------------------------------------------------------------------
    // 4. P3: an async refresh FAILURE changes nothing — old keyset, ready, lastOkFetch all survive,
    //    no self-retry, and exactly ONE log line for the attempt (doFetch owns it).
    // ------------------------------------------------------------------------------------------
    @Test
    void asyncFailureKeepsCacheAndReady() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        String tokenK1 = jwt(k1, validClaims("u"));
        var cacheBefore = verify.jwksCache;
        long okBefore;
        httpAttempts.set(0);

        ListAppender<ILoggingEvent> logs = captureJwsLog();
        try {
            jwksStatus = 500; // the IdP blips
            okBefore = ageWindow(verify);

            assertNotNull(verify.useKey("K1"), "a failing refresh must not disturb the request");

            // COMPLETION HANDSHAKE: doFetch logs the failed attempt at ERROR as its last act, so
            // the appearance of that line means the attempt is over (not "not started yet").
            awaitLogLine(logs, "error fetch jwks");
            Thread.sleep(SETTLE_MILLIS); // give any SECOND line / retry time to show up

            assertEquals(1, httpAttempts.get(),
                    "a failed async refresh must not retry itself inside the window");
            assertSame(cacheBefore, verify.jwksCache,
                    "a failed refresh must keep the last-known-good keyset map itself");
            assertTrue(verify.isReady(),
                    "a transient refresh failure must NOT reopen the fail-closed gate");
            assertEquals(okBefore, verify.lastOkFetch,
                    "lastOkFetch advanced on a FAILED attempt (the 5-min window would be burned)");
            assertTrue(verify.lastTryFetch > okBefore,
                    "the attempt did not advance the try-window: " + verify.lastTryFetch);
            assertVerifyOk(verify, tokenK1, "the last-known-good keyset keeps serving through the 500");

            List<String> failureLines = logs.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR || e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("jwks"))
                    .toList();
            assertEquals(1, failureLines.size(),
                    () -> "a failed async attempt must produce EXACTLY ONE log line (doFetch's ERROR); got "
                            + failureLines);
        } finally {
            detachJwsLog(logs);
        }
    }

    // ------------------------------------------------------------------------------------------
    // 5. MISS regression: an unknown kid still refetches SYNCHRONOUSLY — the caller blocks until
    //    the fetch completes and its own request sees the fetched keyset.
    // ------------------------------------------------------------------------------------------
    @Test
    void unknownKidStillFetchesSynchronously() throws Exception {
        Kp k1 = genKey("K1");
        Kp k2 = genKey("K2");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        httpAttempts.set(0);

        // (a) inside the window an unknown kid must not fetch (anti-amplification, unchanged).
        verify.lastTryFetch = System.currentTimeMillis();
        assertNull(verify.useKey("K2"), "unknown kid inside the window");
        assertEquals(0, httpAttempts.get(), "a MISS inside the window must not fetch");

        // (b) outside the window: the fetch is parked, and the CALLER must be parked with it.
        currentJwks = jwksDoc(k1, k2); // the fetch this MISS triggers will carry the new kid
        CountDownLatch release = armGate();
        CountDownLatch entered = armEntered();
        ageWindow(verify);

        var returned = new AtomicReference<ECPublicKey>();
        CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            returned.set(verify.useKey("K2"));
            done.countDown();
        }, "miss-caller").start();

        assertTrue(entered.await(10, TimeUnit.SECONDS), "the MISS outside the window did not fetch");
        assertFalse(done.await(SETTLE_MILLIS, TimeUnit.MILLISECONDS),
                "the MISS path returned while its fetch was still parked — it must stay synchronous, "
                        + "otherwise a freshly rotated kid can never be picked up by the request that needs it");
        gate = null;
        release.countDown();

        assertTrue(done.await(10, TimeUnit.SECONDS), "the MISS caller never returned");
        assertNotNull(returned.get(),
                "the MISS caller must see the key its OWN synchronous fetch pulled in");
        assertEquals(1, httpAttempts.get(), "exactly one synchronous MISS attempt");
        assertVerifyOk(verify, jwt(k2, validClaims("u")), "the rotated-in kid verifies");
    }

    // ------------------------------------------------------------------------------------------
    // 6. Single-flight + no stale overwrite: the async refresh takes part in the SAME fetchLock as
    //    loadJwks (max 1 concurrent JWKS request), and a slow older response can never end up as
    //    the live keyset. Phase B: a worker whose claim predates a newer commit drops its attempt.
    // ------------------------------------------------------------------------------------------
    @Test
    void asyncAndSyncFetchSerializeWithNoStaleOverwrite() throws Exception {
        Kp k1 = genKey("K1");
        Kp k2 = genKey("K2");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        httpAttempts.set(0);

        // --- Phase A: async refresh in flight (serving the OLD document) + a concurrent loadJwks.
        CountDownLatch release = armGate();
        CountDownLatch entered = armEntered();
        ageWindow(verify);
        new Thread(() -> verify.useKey("K1"), "hit-caller").start();
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the stale HIT did not start a refresh");
        // The parked fetch captured {K1} at entry; publish the NEWER document now.
        currentJwks = jwksDoc(k1, k2);

        CountDownLatch loadDone = new CountDownLatch(1);
        var loadError = new AtomicReference<Throwable>();
        new Thread(() -> {
            try {
                verify.loadJwks();
            } catch (Throwable t) {
                loadError.set(t);
            } finally {
                loadDone.countDown();
            }
        }, "sync-loader").start();

        assertFalse(loadDone.await(SETTLE_MILLIS, TimeUnit.MILLISECONDS),
                "a synchronous loadJwks completed while the async refresh was mid-fetch: the worker "
                        + "bypassed the fetchLock single-flight");
        assertEquals(1, httpAttempts.get(),
                "two JWKS requests were in flight at once — single-flight bypassed");

        gate = null;
        release.countDown();
        assertTrue(loadDone.await(15, TimeUnit.SECONDS), "the queued loadJwks never completed");
        assertNull(loadError.get(), () -> "loadJwks failed: " + loadError.get());

        assertEquals(1, maxInFlight.get(),
                "at most ONE JWKS request may be in flight at any time (async worker + sync loader "
                        + "share fetchLock)");
        assertEquals(2, httpAttempts.get(), "both attempts really happened (not a vacuous pass)");
        assertEquals(Set.of("K1", "K2"), verify.jwksCache.keySet(),
                "the NEWER response must be the live keyset — an older async response overwrote it");

        // --- Phase B: a claim that predates a newer commit must be DROPPED, not refetched.
        httpAttempts.set(0);
        verify.fetchLock.lock();
        try {
            ageWindow(verify);
            Thread hit = new Thread(() -> verify.useKey("K1"), "hit-caller-2");
            hit.start();
            hit.join(5_000); // the request thread returns; its worker is queued on the lock
            // Commit a NEWER keyset while holding the lock (reentrant from this thread), then make
            // the endpoint serve a STALE document again: a worker that refetches would publish it.
            currentJwks = jwksDoc(k1, k2);
            verify.loadJwks();
            currentJwks = jwksDoc(k1);
        } finally {
            verify.fetchLock.unlock();
        }
        Thread.sleep(SETTLE_MILLIS);
        assertEquals(1, httpAttempts.get(),
                "the worker refetched although a NEWER keyset had been committed after its claim");
        assertEquals(Set.of("K1", "K2"), verify.jwksCache.keySet(),
                "a stale claim's response replaced the newer keyset");

        // Known-positive for that negative assertion: attempts ARE observable right afterwards.
        CountDownLatch entered2 = armEntered();
        ageWindow(verify);
        assertNotNull(verify.useKey("K1"), "HIT still served");
        assertTrue(entered2.await(10, TimeUnit.SECONDS),
                "no attempt is observable at all — the phase-B assertion above was vacuous");
        awaitAttempts(2);
    }

    // ==========================================================================================
    // Harness
    // ==========================================================================================

    /**
     * Model a verifier that has not fetched for more than one window: BOTH clocks are old. Ageing
     * only {@code lastTryFetch} would be an impossible state (a commit "just happened" while the
     * window is stale) in which the worker's freshness re-check legitimately drops the attempt.
     *
     * @return the aged timestamp, which is now the value of both clocks
     */
    private static long ageWindow(JwsVerify v) {
        long aged = System.currentTimeMillis() - JwsVerify.GAP_MILL - 1_000L;
        v.lastOkFetch = aged;
        v.lastTryFetch = aged;
        return aged;
    }

    private CountDownLatch armGate() {
        CountDownLatch latch = new CountDownLatch(1);
        armedGates.add(latch);
        gate = latch;
        return latch;
    }

    private CountDownLatch armEntered() {
        CountDownLatch latch = new CountDownLatch(1);
        fetchEntered = latch;
        return latch;
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

    private void awaitAttempts(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (httpAttempts.get() < expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("expected " + expected + " JWKS attempts, saw " + httpAttempts.get());
            }
            Thread.sleep(10);
        }
    }

    private static void awaitLogLine(ListAppender<ILoggingEvent> logs, String needle)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains(needle))) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("no log line containing '" + needle + "' — the attempt never "
                        + "completed (or stopped logging its failure)");
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

    /** An EC P-256 keypair plus the url-base64 PKCS8 private key the production signer wants. */
    record Kp(String kid, String priB64, ECPublicKey pub) {}

    private static Kp genKey(String kid) throws Exception {
        var kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        String priB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(kp.getPrivate().getEncoded());
        return new Kp(kid, priB64, (ECPublicKey) kp.getPublic());
    }

    private static String validClaims(String sub) {
        return "{\"sub\":\"" + sub + "\",\"exp\":" + (System.currentTimeMillis() / 1000L + 3600) + "}";
    }

    private static String b64(String s) {
        return Es256Signature.base64(s.getBytes(UTF_8));
    }

    private static String jwt(Kp kp, String claimsJson) {
        return new Es256Signature(kp.priB64)
                .sign(b64("{\"kid\":\"" + kp.kid + "\",\"alg\":\"ES256\"}"), b64(claimsJson));
    }

    private static String jwksDoc(Kp... keys) {
        var sb = new StringBuilder("{\"keys\":[");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jwkEntry(keys[i]));
        }
        return sb.append("]}").toString();
    }

    private static String jwkEntry(Kp kp) {
        var w = kp.pub.getW();
        return "{\"kty\":\"EC\",\"use\":\"sig\",\"crv\":\"P-256\",\"kid\":\"" + kp.kid
                + "\",\"x\":\"" + coord(w.getAffineX()) + "\",\"y\":\"" + coord(w.getAffineY()) + "\"}";
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

    /**
     * Loopback JWKS endpoint on a REAL thread pool (not the single dispatcher thread): two
     * concurrent fetches must be able to genuinely overlap, otherwise {@code maxInFlight} could
     * never observe a single-flight breach.
     */
    private void startJwks(String body) throws Exception {
        currentJwks = body;
        jwksStatus = 200;
        jwksPool = Executors.newCachedThreadPool();
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.setExecutor(jwksPool);
        jwksServer.createContext(JWKS_PATH, ex -> {
            httpAttempts.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            // Captured AT ENTRY: a parked fetch serves the document that was live when it started.
            byte[] out = currentJwks.getBytes(UTF_8);
            int status = jwksStatus;
            CountDownLatch park = gate;
            CountDownLatch entered = fetchEntered;
            if (entered != null) {
                entered.countDown();
            }
            try {
                if (park != null) {
                    park.await(30, TimeUnit.SECONDS);
                }
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, out.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        });
        jwksServer.start();
    }

    private String jwksUrl() {
        return "http://127.0.0.1:" + jwksServer.getAddress().getPort() + JWKS_PATH;
    }

    private JwsVerify readyVerifier(String body) throws Exception {
        startJwks(body);
        JwsVerify v = new JwsVerify(jwksUrl());
        v.loadJwks();
        return v;
    }

    private static void assertVerifyOk(JwsVerify v, String token, String label) {
        try {
            assertNotNull(v.verify(token, CID, false), () -> label + " → expected a credential, got null");
        } catch (StatusException e) {
            throw new AssertionError(label + " → expected OK but was rejected: "
                    + e.getStatus().getCode() + " (\"" + e.getStatus().getDescription() + "\")", e);
        }
    }
}
