/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.server.jws;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.locks.ReentrantLock;

import tech.krpc.util.JsonUtils;
import tech.krpc.util.StringUtils;
import tech.krpc.server.ServerContext;
import io.grpc.Status;
import io.grpc.StatusException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Martin.C
 * @version 2021/11/17 10:52 AM
 */
@Slf4j
public class JwsVerify implements CredentialVerify {

    public static final String WELL_KNOWN_JWKS_PATH = ".well-known/jwks.json";
    public static final String DEFAULT_COOKIE_NAME = "access-token";

    // O3/O4 (HARDEN-B1) + anti-amplification (owner decision): ONE throttle window shared by BOTH
    // the periodic HIT-path freshness refresh and the unknown-kid/post-failure MISS-path refetch —
    // there used to be two independent constants here (this one at 5 min, and a separate 30s
    // MIN_FETCH_GAP_MILL for the MISS path so rotation was picked up fast). That second, SHORTER
    // window was the bug: the MISS path is ATTACKER-CONTROLLED (anyone can mint a JWT header
    // carrying a random kid, no valid credential required), so a 30s window let a kid-spray turn
    // every server instance into a source hammering the JWKS origin (a downstream h5 CDN) at 10x
    // the rate legitimate traffic would ever produce.
    //
    // THE INVARIANT this single constant now guarantees: an unknown-kid refetch can never happen
    // more often than the refetch legitimate steady-state traffic already causes on its own (at
    // most once per instance per window) — a random-kid spray cannot push the origin fetch rate
    // above that baseline, because both paths spend from the same clock (lastTryFetch). Do not
    // reintroduce a second, shorter constant for the MISS path — that reopens the amplification gap
    // this unification closes.
    //
    // Rotation-latency tradeoff, accepted by owner: a freshly-rotated-in kid's worst-case pickup
    // latency moves from 30s to this window (5 min), because MISS-path pickup now waits on the same
    // clock as the HIT path. Under normal (non-attack) traffic this is usually invisible — the
    // HIT-path refresh already rebuilds the whole keyset on every fetch, so any request against an
    // existing still-valid kid pulls a newly-published key in as a side effect within this window,
    // and the FIRST request carrying the new kid gets an IMMEDIATE fetch if it lands outside the
    // window. Only a request landing on a kid-spray-pinned window has to wait the full period —
    // the accepted cost of a single constant that cannot be gamed into an amplifier.
    static final long GAP_MILL = 5 * 60 * 1000L;

    // C4 (HARDEN-B1): bound the JWKS fetch so a slow/hostile IdP can neither hang a request
    // thread forever nor OOM us with an unbounded body.
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_JWKS_BYTES = 1 << 20; // 1 MiB

    // advisory (HARDEN-B1 fix-round-1): wall-clock deadline for CONSUMING the response body. The
    // HttpClient request timeout only covers up to the response headers; a slow-drip body needs its
    // own bound. Default = REQUEST_TIMEOUT; package-private + non-final so tests can shorten it.
    static final ExecutorService BODY_READ_POOL =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jwks-body-", 0).factory());
    long bodyReadTimeoutMillis = REQUEST_TIMEOUT.toMillis();

    // O1 (HARDEN-B1): background fail-closed retry backoff — gentle so an IdP blip doesn't
    // hammer it; grows to a ceiling. See bootstrap()/startBackgroundRetry().
    static final long RETRY_INITIAL_MILL = 5 * 1000L;
    static final long RETRY_MAX_MILL = 60 * 1000L;

    // O-sec-17 (HARDEN-B1): allow small clock drift between issuer and verifier for nbf.
    static final long CLOCK_SKEW_SEC = 60L;

    // O3 (HARDEN-B1): whole-map REPLACE (not merge) on every successful fetch, so revoked/rotated
    // keys disappear. volatile → readers see the new keyset atomically without locking.
    volatile Map<String, ECPublicKey> jwksCache = new ConcurrentHashMap<>();

    // O4 (HARDEN-B1): lastOkFetch advances ONLY on success (tests/observability read it to prove a
    // failed attempt didn't move it); lastTryFetch is the sole clock the window gates against —
    // see GAP_MILL for why HIT and MISS share this one clock.
    //
    // KRPC-JWKS-001: lastTryFetch is written through exactly ONE place, claimFetchAttempt(), which
    // stamps it atomically at CLAIM time, before the attempt's I/O. That single advance point is
    // what makes the off-thread HIT refresh safe: N concurrent HITs CAS for one claim, so the
    // window still admits at most one attempt. Do NOT add a second timestamp or a second advance
    // point (e.g. re-stamping inside doFetch) — the two paths' windows would drift apart.
    volatile long lastOkFetch;
    volatile long lastTryFetch;

    private static final AtomicLongFieldUpdater<JwsVerify> LAST_TRY_FETCH =
            AtomicLongFieldUpdater.newUpdater(JwsVerify.class, "lastTryFetch");

    // KRPC-JWKS-001 fix-round-1: COMMIT CAUSALITY, not wall clock. Every successful commit in
    // doFetch() bumps this counter (always under fetchLock, so ++ needs no CAS). The async worker
    // captures it at claim time and compares under the lock: a DIFFERENT value means some commit
    // landed after the claim, so the attempt is dropped. Timestamps cannot express that ordering —
    // a backwards clock jump makes a newer commit look older (lastOkFetch < claimedAt), which sent
    // the worker off to fetch again and reopened a window for an older view to be published after
    // a newer one. Monotonic and never reset: the only comparison that matters is same/different.
    volatile long commitGeneration;

    volatile Jwks lastJwks;

    // O1 (HARDEN-B1): fail-closed readiness. false until the FIRST successful fetch. While false,
    // verify() rejects every request (UNAVAILABLE) — never fail-open. Once true it stays true;
    // later refresh failures keep serving the last-known-good keyset (availability), they do not
    // reopen the fail-closed gate.
    volatile boolean ready;

    // O1 (HARDEN-B1): guards against spawning more than one background retry daemon.
    final AtomicBoolean retrying = new AtomicBoolean(false);

    // Single-flight fetch guard. ReentrantLock (NOT synchronized) so a virtual thread parks
    // instead of pinning its carrier across the blocking JWKS fetch.
    final ReentrantLock fetchLock = new ReentrantLock();

    final HttpClient httpClient;

    final String url;

    final ExtVerify extVerify;

    @Getter
    final String cookieName;

    final boolean bindClient;

    // O-sec-17 (HARDEN-B1): optional aud validation. Empty (default) = OFF, behaviour unchanged.
    // When non-empty, the token's aud must intersect this set. Off by default to avoid breaking
    // single-aud deployments; real enablement is a follow-up (see SPEC §8).
    volatile List<String> requiredAudiences = List.of();

    public JwsVerify(String url) {
        this(url, DEFAULT_COOKIE_NAME);
    }

    public JwsVerify(String url, String cookieName) {
        this(url, cookieName, ExtVerify.EMPTY, false);
    }

    public JwsVerify(String url, String cookieName, ExtVerify extVerify, boolean bindClient) {
        if (!url.endsWith(".json")) {
            url += url.endsWith("/") ? WELL_KNOWN_JWKS_PATH : "/" + WELL_KNOWN_JWKS_PATH;
        }
        this.url = url;
        this.cookieName = cookieName;
        this.extVerify = extVerify;
        this.bindClient = bindClient;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** O-sec-17 (HARDEN-B1): configure optional aud validation. Null/empty = OFF (default). */
    public JwsVerify requiredAudiences(List<String> audiences) {
        this.requiredAudiences = (audiences == null) ? List.of() : List.copyOf(audiences);
        return this;
    }

    public String getUrl() {
        return url;
    }

    boolean isReady() {
        return ready;
    }

    ECPublicKey useKey(String kid) {
        var cache = jwksCache;
        var key = cache.get(kid);
        if (null != key) {
            // O3 (HARDEN-B1) + KRPC-JWKS-001: on a HIT still refresh periodically so revocations
            // take effect — but NEVER on this thread (see refreshAsyncIfStale). Re-read the volatile
            // afterwards: if a worker committed a revocation in the meantime this request already
            // sees the empty/rebuilt keyset.
            refreshAsyncIfStale();
            return jwksCache.get(kid);
        }
        // O4 (HARDEN-B1) + anti-amplification: a MISS may be a fresh rotation — refetch
        // SYNCHRONOUSLY (this request has no key to serve, so there is nothing to be stale about),
        // on the SAME GAP_MILL window as the HIT path above (see GAP_MILL javadoc for why: a MISS is
        // attacker-controlled and must not get a shorter, gameable window).
        maybeRefetch();
        return jwksCache.get(kid);
    }

    /**
     * KRPC-JWKS-001 (stale-while-revalidate, owner-accepted): the periodic HIT-path freshness
     * refresh, moved OFF the request thread. The request thread does ZERO JWKS I/O and NEVER takes
     * or waits on {@link #fetchLock}: it only claims the window (one CAS) and hands the fetch to a
     * virtual thread, then keeps serving the key it already has.
     *
     * <p>SEMANTIC TRADEOFF, accepted by owner: this request — and every concurrent request until
     * the refresh commits — is served from the possibly one-window-stale keyset, so a revocation
     * takes effect at worst one fetch duration (bounded by CONNECT/REQUEST/body-read timeouts)
     * later than before. The cost it buys off is a cold-TLS JWKS round-trip (65-700ms measured)
     * inside the authentication path. A refresh that comes back with ZERO usable keys still fails
     * closed immediately on commit (O3/B3 in {@link #doFetch}).
     */
    void refreshAsyncIfStale() {
        if (claimFetchAttempt(true) == 0L) {
            return; // inside the window, or another request already owns this window's attempt
        }
        // Captured AFTER the claim: the worker only has to recognise commits that land from here on.
        long claimedGeneration = commitGeneration;
        try {
            startRefreshWorker(claimedGeneration);
        } catch (RuntimeException | Error e) {
            // The claim is deliberately NOT rolled back: this window is spent and the next one
            // retries. Rolling back would turn a thread-start failure into a spawn/claim storm
            // against the JWKS origin (anti-amplification, see GAP_MILL). One line, no rethrow —
            // the request already holds its key and must not fail over a refresh.
            log.warn("jwks async refresh not started, window spent : {} : {}", url, e.toString());
        }
    }

    /** Spawns the refresh worker on a daemon virtual thread. Own method so tests can observe it. */
    void startRefreshWorker(long claimedGeneration) {
        Thread.ofVirtual().name("jwks-refresh-" + url).start(() -> refreshClaimed(claimedGeneration));
    }

    /**
     * The worker half of the HIT-path refresh. It PARTICIPATES in the existing {@link #fetchLock}
     * single-flight (a blocking lock parks this virtual thread instead of pinning its carrier), so
     * at most one JWKS request is ever in flight and every commit is serialized — a slower older
     * response can never overwrite a newer whole-map replace (O3), because there is no commit path
     * outside this lock.
     *
     * <p>Holding the lock it re-checks {@link #commitGeneration} against the value captured at
     * claim time: if a synchronous {@link #loadJwks()}, a MISS refetch or the background retry
     * committed a keyset after this claim, the generation differs and the attempt is DROPPED
     * instead of repeated. The check is on commit ORDER, never on timestamps: two wall-clock
     * readings cannot tell "committed after my claim" from "the clock jumped backwards", and
     * getting that wrong both duplicates the origin fetch and re-opens the stale-overwrite window
     * this method exists to close.
     */
    void refreshClaimed(long claimedGeneration) {
        fetchLock.lock();
        try {
            if (commitGeneration != claimedGeneration) {
                return; // a commit landed after our claim — this attempt is redundant
            }
            doFetch();
        } catch (RuntimeException e) {
            // P3/GR-007 (as narrowed by the R1 errata: the "exactly one line" rule is about the
            // ASYNC path): doFetch already logged THIS attempt's failure at ERROR, so the async
            // wrapper adds no second line. Swallowed on purpose: the last-known-good keyset keeps
            // serving, ready is untouched, lastOkFetch did not move, and an uncaught throw on a
            // virtual thread would only add noise.
        } finally {
            fetchLock.unlock();
        }
    }

    /**
     * KRPC-JWKS-001: reserve ("claim") a fetch attempt. THE SINGLE WRITER of
     * {@link #lastTryFetch}: one attempt advances the window exactly once, atomically, at claim
     * time, BEFORE any I/O.
     *
     * @param gated {@code true} for the window-gated request paths (HIT async refresh, MISS
     *              refetch): the claim succeeds only once {@link #GAP_MILL} has elapsed AND only
     *              for ONE of N racing callers. {@code false} for the ungated callers (bootstrap
     *              {@link #loadJwks()} and the background retry): they always attempt, and they
     *              DO stamp the window clock — see {@link #loadJwks()} for why that is the wanted
     *              behaviour and not an oversight.
     * @return the claim timestamp, or {@code 0} when this caller did NOT win an attempt and must
     *         not fetch. Losing is always the safe side: fewer origin fetches, never more.
     */
    long claimFetchAttempt(boolean gated) {
        for (;;) {
            long last = lastTryFetch;
            long now = System.currentTimeMillis();
            if (gated && now - last < GAP_MILL) {
                return 0L; // still inside the window (also covers a backwards clock jump: no fetch)
            }
            if (LAST_TRY_FETCH.compareAndSet(this, last, now)) {
                return now;
            }
            if (gated) {
                return 0L; // a concurrent claimer took this window's attempt
            }
            // ungated: lost only the stamp, not the attempt — restamp and go.
        }
    }

    /**
     * O4 (HARDEN-B1) + anti-amplification: throttled, best-effort SYNCHRONOUS refetch for the
     * unknown-kid MISS request path (see {@link #useKey}; the HIT path refreshes off-thread via
     * {@link #refreshAsyncIfStale}). Both paths gate on the ONE {@link #GAP_MILL} window through
     * {@link #claimFetchAttempt} — a caller-selectable shorter window on the MISS path is exactly
     * the amplification surface that unification closed. Non-blocking on contention: if another
     * thread (or the async refresh worker) already holds the fetch lock we skip (single-flight); a
     * failed fetch is swallowed so the last-known-good keyset keeps serving, and it does NOT
     * advance {@link #lastOkFetch}. NOTE what is and is not preserved: the ATTEMPT window is
     * already spent — {@link #claimFetchAttempt} advanced {@link #lastTryFetch} before the I/O and
     * never rolls it back, so the next attempt waits a full {@link #GAP_MILL} (deliberate: a
     * failing origin must not be retried harder). Only the SUCCESS timestamp survives the failure.
     */
    void maybeRefetch() {
        if (System.currentTimeMillis() - lastTryFetch < GAP_MILL) {
            return;
        }
        if (!fetchLock.tryLock()) {
            return; // another thread is already fetching
        }
        try {
            if (claimFetchAttempt(true) == 0L) {
                return; // lost the race, someone just claimed/fetched this window
            }
            try {
                doFetch();
            } catch (RuntimeException e) {
                log.warn("jwks refetch failed, keeping last-known keys : {} : {}", url, e.getMessage());
            }
        } finally {
            fetchLock.unlock();
        }
    }

    /**
     * Force a synchronous fetch, throwing on failure. Used by bootstrap and the background retry
     * (and by tests that want eager loading). Blocking under {@link #fetchLock}; on a virtual
     * thread the VT parks (ReentrantLock + NIO HttpClient) without pinning its carrier.
     *
     * <p>UNGATED but still stamping: {@link #GAP_MILL} never throttles this call, yet it claims
     * (ungated) so the window clock advances exactly where the pre-KRPC-JWKS-001 attempt stamped it
     * (the first line of {@code doFetch}). Keeping that stamp is the anti-amplification side: a
     * bootstrap or retry fetch that did NOT stamp would leave the window open, so the very first
     * request after startup would immediately claim a second origin fetch. Byte-for-byte the
     * pre-existing behaviour (R1 errata #1).
     */
    public void loadJwks() {
        fetchLock.lock();
        try {
            claimFetchAttempt(false);
            doFetch();
        } finally {
            fetchLock.unlock();
        }
    }

    /**
     * C4/O3/O-sec-47 (HARDEN-B1): fetch JWKS with timeout + body cap, then REBUILD the keyset map
     * (replace, not merge). Advances, only on success, {@link #lastOkFetch} and
     * {@link #commitGeneration} (and {@link #ready} on the first load); the attempt's
     * {@link #lastTryFetch} stamp already happened at claim time
     * (KRPC-JWKS-001: {@link #claimFetchAttempt} is the single advance point — do not re-stamp
     * here). B3 (fix-round-1): a successful fetch yielding ZERO usable keys is a REVOCATION — when
     * already ready it REPLACES the live map with an empty one and returns (fail-closed, no throw
     * so maybeRefetch can't keep stale keys); before the first success it throws and stays
     * not-ready. Never NPEs on null keys.
     * Caller MUST hold {@link #fetchLock} AND have claimed the attempt via
     * {@link #claimFetchAttempt}.
     */
    void doFetch() {
        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> resp =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("jwks http status " + resp.statusCode());
            }
            byte[] body;
            try (InputStream in = resp.body()) {
                // C4 (HARDEN-B1): read at most MAX_JWKS_BYTES+1; overflow ⇒ reject (anti-OOM).
                // advisory (HARDEN-B1 fix-round-1): the 1 MiB cap bounds SIZE but ofInputStream +
                // readNBytes has no wall clock (the HttpClient request timeout only covers up to the
                // response headers) — a hostile IdP could drip the body forever and pin this thread.
                // Read under an explicit deadline; on timeout close the stream to abort the read.
                body = readBodyBounded(in);
            }
            if (body.length > MAX_JWKS_BYTES) {
                throw new RuntimeException("jwks body exceeds " + MAX_JWKS_BYTES + " bytes");
            }
            var json = new String(body, StandardCharsets.UTF_8);
            var jwks = JsonUtils.parse(json, Jwks.class);

            // O3/O-sec-47 (HARDEN-B1): build the fresh keyset from the fetched document.
            //
            // SECURITY BOUNDARY -- tolerance is per-JWK, never per-document. A keyset routinely
            // mixes key types this verifier does not implement, and RFC 7517 lets a JWK carry
            // members of any JSON type, so ONE unusable entry must skip rather than abort the
            // refresh. Aborting is the dangerous outcome, not the safe one: the throw is swallowed
            // by maybeRefetch(), so the previous cache stays live -- an IdP that withdraws a
            // compromised key, publishes its replacement, and happens to also serve one malformed
            // entry would leave the REVOKED key verifying signatures indefinitely. Everything that
            // can fail for a single JWK is therefore inside the try: member reads, blank checks,
            // base64 decoding, curve lookup and EC point construction.
            var rebuilt = new ConcurrentHashMap<String, ECPublicKey>();
            if (jwks != null && jwks.keys != null) {
                for (var jwk : jwks.keys) {
                    if (!Es256Jwk.ELLIPTIC_CURVE.equals(stringMember(jwk, Jwks.KEY_TYPE))) {
                        continue;
                    }
                    var kid = stringMember(jwk, PublicClaims.KEY_ID);
                    var x = stringMember(jwk, "x");
                    var y = stringMember(jwk, "y");
                    var crv = stringMember(jwk, "crv");
                    // A blank kid is unusable: verify() rejects a blank kid outright, so caching
                    // one would inflate the keyset and flip ready=true without contributing a key
                    // that can ever authenticate anything -- hiding a real zero-key revocation.
                    if (StringUtils.isBlank(kid) || null == x || null == y || null == crv) {
                        log.warn("skipping unusable EC JWK (kid blank, or kid/x/y/crv missing or"
                                + " not a string) from {}", url);
                        continue;
                    }
                    try {
                        rebuilt.put(kid, new Es256Jwk(kid, x, y, crv).toECPublicKey());
                    } catch (Exception keyEx) {
                        // Well-formed strings, unusable key material: bad base64, empty/oversized
                        // coordinates, an unsupported curve, a point off the curve.
                        log.warn("skipping EC JWK kid={} from {}: cannot build public key ({})",
                                kid, url, keyEx.toString());
                    }
                }
            }

            // B3 (HARDEN-B1 fix-round-1): a SUCCESSFUL fetch that definitively yields ZERO usable
            // keys (empty/null keys array, or only non-EC entries) is NOT a fetch failure — it is
            // a REVOCATION signal ("all keys withdrawn"). Two cases, split on readiness:
            //   • already ready: REPLACE the live map with the empty one and fail closed — every
            //     kid now misses → PERMISSION_DENIED. Crucially we must NOT throw here: a throw is
            //     swallowed by maybeRefetch(), which would leave the STALE keyset live and keep
            //     serving revoked tokens (the regression this fixes / O3). It was a real fetch, so
            //     advance lastOkFetch; ready stays true.
            //   • not yet ready (bootstrap): stay fail-closed + not-ready and THROW, so scheme (b)
            //     aborts startup and scheme (a) keeps the background retry running (→ UNAVAILABLE).
            if (rebuilt.isEmpty()) {
                if (ready) {
                    jwksCache = rebuilt;
                    lastJwks = jwks;
                    lastOkFetch = System.currentTimeMillis();
                    commitGeneration++; // KRPC-JWKS-001: a commit IS a generation (under fetchLock)
                    log.warn("!!! JWKS fetched with NO usable keys — treating as full revocation, "
                            + "failing CLOSED (every token now rejected) : {}", url);
                    return;
                }
                throw new RuntimeException("jwks has no usable keys");
            }

            // O3 (HARDEN-B1): REPLACE the reference (revocation/rotation), never merge.
            jwksCache = rebuilt;
            lastJwks = jwks;
            lastOkFetch = System.currentTimeMillis();
            commitGeneration++; // KRPC-JWKS-001: a commit IS a generation (under fetchLock)
            ready = true;
            log.info("success fetch jwks : {}", rebuilt.keySet());
        } catch (RuntimeException e) {
            log.error("error fetch jwks : {} : {}", url, e.getMessage());
            throw e;
        } catch (Exception e) {
            // checked exceptions from send()/toECPublicKey() → uniform runtime failure.
            log.error("error fetch jwks : {} : {}", url, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Read one JWK member as a string, or {@code null} if it is absent or carries a non-string
     * JSON value. RFC 7517 §4 permits any JSON type for a member, so this verifier reads only what
     * it consumes and treats anything else as "this JWK is not usable by ES256" rather than as a
     * malformed document.
     */
    private static String stringMember(Map<String, Object> jwk, String name) {
        return jwk.get(name) instanceof String s ? s : null;
    }

    /**
     * advisory (HARDEN-B1 fix-round-1): read at most {@code MAX_JWKS_BYTES+1} bytes under a
     * wall-clock deadline ({@link #bodyReadTimeoutMillis}). Runs the blocking read on a virtual
     * thread; if it overruns, the source stream is closed to unblock it and a failure is thrown so
     * the fetch is treated as failed (last-known-good keyset kept). Caller MUST hold the fetch lock.
     */
    byte[] readBodyBounded(InputStream in) {
        var task = CompletableFuture.supplyAsync(() -> {
            try {
                return in.readNBytes(MAX_JWKS_BYTES + 1);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, BODY_READ_POOL);
        try {
            return task.get(bodyReadTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            try {
                in.close(); // abort the blocked readNBytes on the worker VT
            } catch (IOException ignore) {
                // best-effort; the read task is already doomed
            }
            task.cancel(true);
            throw new RuntimeException("jwks body read timed out after " + bodyReadTimeoutMillis + "ms");
        } catch (ExecutionException ee) {
            throw new RuntimeException(ee.getCause());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("jwks body read interrupted");
        }
    }

    /**
     * O1 (HARDEN-B1): fail-closed startup. Scheme (a): try one synchronous load; on failure the
     * verifier is left NOT ready (verify() rejects every request — never fail-open) and a gentle
     * background retry is started. Scheme (b): {@code exitOnJwksError=true} rethrows so startup
     * aborts loudly. Either way there is no silent fail-open path.
     */
    public void bootstrap(boolean exitOnJwksError) {
        try {
            loadJwks();
        } catch (RuntimeException e) {
            if (exitOnJwksError) {
                throw e; // scheme (b): abort startup
            }
            // scheme (a): stay up but FAIL CLOSED, retry in the background.
            log.error("!!! JWKS load FAILED at startup — auth is FAIL-CLOSED (all credentialed "
                    + "requests rejected as UNAVAILABLE) until JWKS becomes reachable : {} : {}",
                    url, e.getMessage());
            startBackgroundRetry();
        }
    }

    /** O1 (HARDEN-B1): single daemon that retries loadJwks with 5s→60s backoff until ready. */
    void startBackgroundRetry() {
        if (!retrying.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("jwks-retry-" + url).start(() -> {
            long backoff = RETRY_INITIAL_MILL;
            while (!ready) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    loadJwks();
                    log.warn("!!! JWKS now reachable — auth is LIVE again (fail-closed lifted) : {}", url);
                } catch (RuntimeException e) {
                    backoff = Math.min(backoff * 2, RETRY_MAX_MILL);
                }
            }
            retrying.set(false);
        });
    }

    /**
     * O1 (HARDEN-B1): construct + fail-closed bootstrap + register, in ONE place shared by every
     * framework module. The verifier is ALWAYS registered (unless scheme (b) aborts startup), so
     * there is no code path that leaves {@code ServerContext.credentialVerify == null} while a JWKS
     * URL is configured — i.e. no fail-open. Returns the registered verifier.
     */
    public static JwsVerify bootstrapAndRegister(String url, String cookieName, ExtVerify extVerify,
                                                 boolean bindClient, boolean exitOnJwksError,
                                                 List<String> requiredAudiences) {
        var verify = new JwsVerify(url, cookieName, extVerify, bindClient)
                .requiredAudiences(requiredAudiences);
        verify.bootstrap(exitOnJwksError);
        ServerContext.regCredentialVerify(verify);
        return verify;
    }

    @Override
    public UserCredential verify(String token, String cid, boolean isCookie) throws StatusException {

        // O1 (HARDEN-B1): FAIL-CLOSED gate. JWKS never loaded ⇒ reject every request. UNAVAILABLE
        // (not UNAUTHENTICATED) so ops can tell "auth backend / JWKS not ready" from a bad token.
        if (!ready) {
            throw Status.UNAVAILABLE
                    .withDescription("JWKS not ready: auth backend unavailable (fail-closed)")
                    .asException();
        }

        if (null == token || token.isBlank()) {
            throw Status.UNAUTHENTICATED.withDescription("requireCredential but empty token").asException();
        }

        // C5 (HARDEN-B1): a malformed token (no dots / bad base64 / bad json / bad header) must map
        // to a clean UNAUTHENTICATED — never a wrapped UNKNOWN/500 — and must NOT flood log.error
        // with a full stack trace (attacker-driven DoS).
        JwsCredential jws;
        String kid;
        String alg;
        byte[] data;
        byte[] signature;
        try {
            jws = new JwsCredential(token);
            kid = jws.getKeyId();
            // O-sec (HARDEN-B1 fix-round-4): read the header `alg` INSIDE this catch. header is
            // parsed as Map<String,String>, so a non-string alg ({"alg":256} / {"alg":[...]}) makes
            // getAlgorithm()'s String checkcast throw ClassCastException — caught here as malformed
            // rather than escaping as UNKNOWN. A missing alg yields null, handled by the guard below.
            alg = jws.getAlgorithm();
            data = jws.jwtWithoutSign().getBytes(StandardCharsets.UTF_8);
            signature = Base64.getUrlDecoder().decode(jws.sign64());
        } catch (RuntimeException e) {
            throw malformed("malformed token", e);
        }

        // O-sec (HARDEN-B1 fix-round-4): alg-conformance guard — CONFORMANCE / DEFENSE-IN-DEPTH,
        // NOT a fail-open fix. The security property (no alg-confusion / alg=none / RS256-with-EC-key)
        // is ALREADY structurally guaranteed: the server always verifies with hardcoded
        // SHA256withECDSA (Es256Jwk.SING_ALGORITHM) over EC-only keys and ignores the client alg, so
        // only a cryptographically valid ES256 signature can pass. This guard adds RFC 7515 §4.1.1
        // header conformance and cleaner reject semantics: a header whose alg is missing or not
        // exactly "ES256" (wrong value; wrong type already caught above) is turned away up front as
        // UNAUTHENTICATED, instead of relying on the signature "happening" not to match.
        if (!Es256Jwk.JWS_ALG.equals(alg)) {
            throw malformed("unsupported or missing alg (require " + Es256Jwk.JWS_ALG + ")", null);
        }

        // B2 (HARDEN-B1 fix-round-1): a well-formed header JSON with no `kid` yields kid == null.
        // useKey → jwksCache.get(null) NPEs on ConcurrentHashMap (null keys forbidden), and that
        // NPE escapes verify() as UNKNOWN. Reject a missing/blank kid up front as UNAUTHENTICATED
        // (malformed token), never touching the map.
        if (null == kid || kid.isBlank()) {
            throw malformed("token missing kid", null);
        }

        var key = useKey(kid);
        if (null == key) {
            throw Status.PERMISSION_DENIED.withDescription("kid not found or expired : " + kid).asException();
        }

        try {
            var valid = Es256Jwk.isValid(data, signature, key);
            if (!valid) {
                throw Status.PERMISSION_DENIED.withDescription("invalid signature !").asException();
            }
        } catch (IllegalArgumentException e) {
            // O-sec-16 (HARDEN-B1): non-64-byte / bare-DER / empty signature is a malformed token.
            throw malformed("malformed signature", e);
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw Status.PERMISSION_DENIED.withCause(e).asException();
        }

        // Signature verified. Read + type-check every payload claim under ONE catch. RED LINE: any
        // malformed path must map to a clean status, never escape as UNKNOWN. A malformed CLAIM here
        // is a wrong JSON *type* — {"exp":"x"} / {"nbf":[...]} deserializes a String/List where a
        // Number is required, and the (Number) casts in getExpiresAt/getNotBefore/getClientHashLong
        // (and the (List) cast behind getAudience) throw a ClassCastException. Pre-fix that CCE
        // escaped verify() as UNKNOWN; catching every type-sensitive claim here (not just exp)
        // closes exp/nbf/chl/aud and any future numeric claim in one place. This block also
        // encloses extVerify.afterSignCheck (see below) so a custom ExtVerify reading iat (or any
        // other claim) cannot escape as UNKNOWN either. StatusException is CHECKED (not a
        // RuntimeException), so the auth rejections thrown below / by extVerify sail through this
        // catch unchanged — only the type/parse faults are remapped to UNAUTHENTICATED.
        try {
            jws.parsePayload();

            var now = System.currentTimeMillis() / 1000L;

            // C5 (HARDEN-B1): a token with no exp is malformed → UNAUTHENTICATED (was NPE→UNKNOWN).
            var exp = jws.getExpiresAt();
            if (exp == null) {
                throw malformed("token missing exp", null);
            }
            if (now > exp.longValue()) {
                throw Status.UNAUTHENTICATED.withDescription("Token expired at: " + exp).asException();
            }

            // O-sec-17 (HARDEN-B1): enforce nbf when present (with small clock-skew allowance).
            var nbf = jws.getNotBefore();
            if (nbf != null && now + CLOCK_SKEW_SEC < nbf.longValue()) {
                throw Status.UNAUTHENTICATED.withDescription("Token not yet valid (nbf): " + nbf).asException();
            }

            // O-sec-17 (HARDEN-B1): optional aud validation (OFF by default; see requiredAudiences).
            var required = requiredAudiences;
            if (!required.isEmpty()) {
                var aud = jws.getAudience();
                if (aud == null || aud.stream().noneMatch(required::contains)) {
                    throw Status.UNAUTHENTICATED.withDescription("Token audience not accepted").asException();
                }
            }

            if (bindClient) {
                var clientHash = jws.getClientHashLong();
                if (null == clientHash || clientHash.longValue() != Murmur3.hash64(cid.getBytes(StandardCharsets.UTF_8))) {
                    throw Status.UNAUTHENTICATED.withDescription("Token forge : " + cid).asException();
                }
            }

            // HARDEN-B1 fix-round-3 (AUDIT-001): extVerify runs INSIDE this catch on purpose. A
            // custom ExtVerify reading any claim (e.g. iat via getIssuedAt's raw (Number) cast) can
            // throw ClassCastException/NPE on a malformed type; enclosing it here maps that to
            // UNAUTHENTICATED instead of letting it escape verify() as UNKNOWN. StatusException is
            // checked, so a legitimate auth rejection thrown by extVerify sails through unchanged.
            // INVARIANT: moving this call out of the catch reopens the UNKNOWN escape.
            extVerify.afterSignCheck(jws, isCookie);
        } catch (RuntimeException e) {
            throw malformed("malformed claim", e);
        }

        // DEBUG (not INFO): success is the hot-path steady state — logging it at INFO would flood
        // production logs on every authenticated call. DEBUG keeps the line available for forensics
        // (turn it on temporarily to positively confirm a kid was accepted) without a standing cost,
        // consistent with this repo's log-level discipline (see observability-standard). No token,
        // signature, or principal bytes — kid only.
        if (log.isDebugEnabled()) {
            log.debug("jws verify ok : kid={}", kid);
        }

        return jws;
    }

    /**
     * C5 (HARDEN-B1): uniform malformed-token rejection. Logs at DEBUG only (message, no stack, no
     * token bytes) so a flood of junk tokens cannot fill logs; returns a clean UNAUTHENTICATED.
     */
    private static StatusException malformed(String what, Throwable cause) {
        if (log.isDebugEnabled()) {
            log.debug("reject malformed jwt: {} : {}", what, cause == null ? "" : cause.getMessage());
        }
        return Status.UNAUTHENTICATED.withDescription(what).asException();
    }

}
