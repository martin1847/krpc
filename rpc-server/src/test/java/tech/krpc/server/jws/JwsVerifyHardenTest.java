/**
 * HARDEN-B1 security regression suite for the JWT/JWKS auth trust root.
 *
 * <p>Every test defends ONE externally observable auth invariant and asserts the EXACT
 * {@link io.grpc.Status.Code} the production contract promises — never a bare "it threw". The RED LINE
 * (test {@link #failClosed_bootstrapRegistersButRejects_onLoadFailure()}) proves no code path can
 * fail-open: a load failure must leave the verifier REGISTERED and REJECTING, never {@code null}.
 *
 * <p>Package is {@code tech.krpc.server.jws} on purpose: it reads package-private
 * {@code isReady()}, {@code lastOkFetch}, {@code lastTryFetch}, {@code ready}, {@code useKey},
 * {@code maybeRefetch}, and {@code Es256Jwk.isValid}. The harness is inlined (copied, not imported,
 * from {@code test.krpc.auth.GrpcContextAuthIT}).
 */
package tech.krpc.server.jws;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import io.grpc.Status;
import io.grpc.StatusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import tech.krpc.server.ServerContext;

class JwsVerifyHardenTest {

    static final String JWKS_PATH = "/.well-known/jwks.json";
    static final String CID = "cid";

    // Single loopback JWKS server per test; handler serves these volatile fields so ONE running
    // server can flip its body/status between fetches (rotation, revocation, transient 5xx).
    HttpServer jwksServer;
    volatile String currentJwks;
    volatile int jwksStatus = 200;

    @AfterEach
    void tearDown() {
        if (jwksServer != null) {
            jwksServer.stop(0);
            jwksServer = null;
        }
        // MUST reset the STATIC verifier so a registered instance never leaks into the next test.
        ServerContext.regCredentialVerify(null);
    }

    // ------------------------------------------------------------------------------------------
    // 1. FAIL-CLOSED bootstrap — THE RED LINE.
    // ------------------------------------------------------------------------------------------
    @Test
    void failClosed_bootstrapRegistersButRejects_onLoadFailure() throws Exception {
        // Pre-fix, a JWKS load failure at startup left ServerContext.credentialVerify == null, so
        // checkCredential's `if (credentialVerify != null)` was skipped entirely — auth silently
        // DISABLED (fail-open). The contract: bootstrap MUST still REGISTER the verifier, and the
        // registered verifier MUST REJECT every credentialed request with UNAVAILABLE.
        String refused = refusedUrl();
        String validLooking = jwt(genKey("K1"), validClaims("user-x")); // structurally perfect token

        JwsVerify verify = JwsVerify.bootstrapAndRegister(
                refused, JwsVerify.DEFAULT_COOKIE_NAME, ExtVerify.EMPTY, false, false, List.of());

        // (a) REGISTERED — the anti-fail-open guarantee. A null here is the resurrected bug.
        assertNotNull(ServerContext.credentialVerify(),
                "load failure left credentialVerify == null → auth skipped (fail-open regression)");
        assertFalse(verify.isReady(), "verifier must NOT be ready after a failed JWKS load");

        // (b) REJECTS — a valid-looking token is turned away because JWKS never loaded. UNAVAILABLE
        //     (not UNAUTHENTICATED) so ops can tell "auth backend down" from "bad token".
        assertVerifyCode(verify, validLooking, Status.Code.UNAVAILABLE,
                "fail-closed: unloaded JWKS must reject a valid-looking token as UNAVAILABLE");
    }

    // ------------------------------------------------------------------------------------------
    // 2. exitOnJwksError=true + refused URL ⇒ startup ABORTS (throws), nothing registered.
    // ------------------------------------------------------------------------------------------
    @Test
    void exitOnJwksError_abortsStartup_whenJwksUnreachable() throws Exception {
        String refused = refusedUrl();

        assertThrows(RuntimeException.class,
                () -> JwsVerify.bootstrapAndRegister(refused, JwsVerify.DEFAULT_COOKIE_NAME,
                        ExtVerify.EMPTY, false, /* exitOnJwksError */ true, List.of()),
                "exitOnJwksError=true must rethrow the load failure to abort startup");

        // Abort happens BEFORE register — scheme (b) dies loudly rather than running fail-open.
        assertNull(ServerContext.credentialVerify(),
                "aborted startup must not register a broken verifier");
    }

    // ------------------------------------------------------------------------------------------
    // 3. Not-ready verifier (never loaded) rejects ANY token with UNAVAILABLE.
    // ------------------------------------------------------------------------------------------
    @Test
    void notReady_rejectsEveryToken_asUnavailable() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = new JwsVerify("http://127.0.0.1:1/x.json"); // constructed, never loaded

        assertVerifyCode(verify, jwt(k1, validClaims("u")), Status.Code.UNAVAILABLE,
                "not-ready verifier must reject a well-formed token");
        assertVerifyCode(verify, "not.a.token", Status.Code.UNAVAILABLE,
                "not-ready verifier must reject a garbage token before parsing it");
    }

    // ------------------------------------------------------------------------------------------
    // 4. Malformed-token matrix (verifier READY) ⇒ each UNAUTHENTICATED, never UNKNOWN/500.
    // ------------------------------------------------------------------------------------------
    @Test
    void malformedTokens_mapToUnauthenticated_whenReady() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));

        // Baseline: prove READY actually verifies AND actually checks the signature. A one-char
        // tamper of a real token must be PERMISSION_DENIED (well-formed 64-byte, wrong sig) — this
        // separates "verifier works" from every UNAUTHENTICATED case below.
        String valid = jwt(k1, validClaims("baseline"));
        assertEquals("baseline", assertVerifyOk(verify, valid, "baseline valid token").getSubject(),
                "READY verifier must resolve the JWT subject");
        assertVerifyCode(verify, reSign(valid, flipOneChar(sigOf(valid))), Status.Code.PERMISSION_DENIED,
                "tampered signature must be PERMISSION_DENIED (proves sig is really checked)");

        String p64 = b64(validClaims("u"));               // valid payload segment
        String h64 = header64("K1");                        // valid header carrying a known kid

        assertVerifyCode(verify, "no-dots-at-all", Status.Code.UNAUTHENTICATED,
                "no dots");
        assertVerifyCode(verify, h64 + ".", Status.Code.UNAUTHENTICATED,
                "single dot (no signature segment)");
        assertVerifyCode(verify, h64 + "." + p64 + ".", Status.Code.UNAUTHENTICATED,
                "empty signature segment (decodes to zero-length sig → length guard)");
        assertVerifyCode(verify, "!!!." + p64 + ".AA", Status.Code.UNAUTHENTICATED,
                "bad base64 in header");
        // Bad base64 in PAYLOAD: sign over the raw bad segment so the SIGNATURE is valid — the
        // failure must surface later at parsePayload(), still as UNAUTHENTICATED (not PERMISSION_DENIED).
        assertVerifyCode(verify, jwtRawPayload(k1, "!!!not-base64!!!"), Status.Code.UNAUTHENTICATED,
                "bad base64 in payload (after a valid signature)");
        assertVerifyCode(verify, b64("not-json-at-all") + "." + p64 + ".AA", Status.Code.UNAUTHENTICATED,
                "header decodes but is not JSON");
    }

    // ------------------------------------------------------------------------------------------
    // 5. Missing exp ⇒ UNAUTHENTICATED.
    // ------------------------------------------------------------------------------------------
    @Test
    void missingExp_isUnauthenticated() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        String noExp = jwt(k1, "{\"sub\":\"u\"}"); // valid sig + kid, but NO exp claim
        assertVerifyCode(verify, noExp, Status.Code.UNAUTHENTICATED,
                "a token with no exp is malformed → UNAUTHENTICATED (not a wrapped NPE/UNKNOWN)");
    }

    // ------------------------------------------------------------------------------------------
    // 6. Expired exp ⇒ UNAUTHENTICATED.
    // ------------------------------------------------------------------------------------------
    @Test
    void expiredToken_isUnauthenticated() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        long now = nowSec();
        String expired = jwt(k1, "{\"sub\":\"u\",\"exp\":" + (now - 10) + "}");
        assertVerifyCode(verify, expired, Status.Code.UNAUTHENTICATED,
                "an exp in the past must be UNAUTHENTICATED");
    }

    // ------------------------------------------------------------------------------------------
    // 7. nbf: beyond skew ⇒ reject; within skew / past ⇒ OK.
    // ------------------------------------------------------------------------------------------
    @Test
    void notBefore_respectsClockSkew() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        long now = nowSec();
        long exp = now + 3600;

        String farFuture = jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"nbf\":" + (now + 300) + "}");
        assertVerifyCode(verify, farFuture, Status.Code.UNAUTHENTICATED,
                "nbf 300s in the future (beyond 60s skew) must be UNAUTHENTICATED");

        String withinSkew = jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"nbf\":" + (now + 30) + "}");
        assertVerifyOk(verify, withinSkew, "nbf 30s ahead is within the 60s skew → OK");

        String past = jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"nbf\":" + (now - 100) + "}");
        assertVerifyOk(verify, past, "nbf in the past → OK");
    }

    // ------------------------------------------------------------------------------------------
    // 8. aud: enforced only when requiredAudiences non-empty; default is OFF (no behaviour change).
    // ------------------------------------------------------------------------------------------
    @Test
    void audience_enforcedOnlyWhenRequired() throws Exception {
        Kp k1 = genKey("K1");
        long exp = nowSec() + 3600;

        JwsVerify enforcing = readyVerifier(jwksDoc(k1)).requiredAudiences(List.of("api-x"));
        assertVerifyCode(enforcing, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + "}"),
                Status.Code.UNAUTHENTICATED, "requiredAudiences set + token has NO aud ⇒ reject");
        assertVerifyCode(enforcing, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"aud\":[\"other\"]}"),
                Status.Code.UNAUTHENTICATED, "requiredAudiences set + disjoint aud ⇒ reject");
        assertVerifyOk(enforcing, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"aud\":[\"api-x\"]}"),
                "requiredAudiences set + intersecting aud ⇒ OK");

        // DEFAULT (no requiredAudiences): a token missing aud must still pass — proves aud is OFF by
        // default and this hardening introduced no behaviour change for single-aud deployments.
        JwsVerify off = new JwsVerify(enforcing.getUrl());
        off.loadJwks();
        assertVerifyOk(off, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + "}"),
                "default (aud check OFF): a token with no aud must be accepted");
    }

    // ------------------------------------------------------------------------------------------
    // 9. Signature malleability (O-sec-16): only exactly-64-raw-byte sigs are accepted.
    // ------------------------------------------------------------------------------------------
    @Test
    void signatureMalleability_nonCanonicalLengthsRejected() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));

        String valid = jwt(k1, validClaims("u"));
        assertVerifyOk(verify, valid, "untampered baseline must verify");

        byte[] raw = Base64.getUrlDecoder().decode(sigOf(valid));   // canonical 64-byte R||S
        // (a) bare ASN.1/DER re-encoding of the same signature — historically accepted (malleable).
        String derSig = Es256Signature.base64(Es256Jwk.jws2der(raw));
        assertVerifyCode(verify, reSign(valid, derSig), Status.Code.UNAUTHENTICATED,
                "bare DER signature (not 64 raw bytes) must be UNAUTHENTICATED");
        // (b) 63- and 65-byte arrays straddling the exact boundary.
        assertVerifyCode(verify, reSign(valid, Es256Signature.base64(new byte[63])),
                Status.Code.UNAUTHENTICATED, "63-byte signature must be UNAUTHENTICATED");
        assertVerifyCode(verify, reSign(valid, Es256Signature.base64(new byte[65])),
                Status.Code.UNAUTHENTICATED, "65-byte signature must be UNAUTHENTICATED");

        // The primitive guard the mapping rests on: empty/null sig ⇒ IllegalArgumentException.
        byte[] data = "x".getBytes(UTF_8);
        assertThrows(IllegalArgumentException.class, () -> Es256Jwk.isValid(data, new byte[0], k1.pub),
                "isValid must reject a zero-length signature");
        assertThrows(IllegalArgumentException.class, () -> Es256Jwk.isValid(data, null, k1.pub),
                "isValid must reject a null signature");
    }

    // ------------------------------------------------------------------------------------------
    // 10. Cryptographically-wrong 64-byte signature ⇒ PERMISSION_DENIED (invalid), NOT UNAUTHENTICATED.
    // ------------------------------------------------------------------------------------------
    @Test
    void wrongSignatureButWellFormed_isPermissionDenied() throws Exception {
        Kp served = genKey("K1");      // this key's public half is in the JWKS
        Kp attacker = genKey("K1");    // different private key, SAME kid
        JwsVerify verify = readyVerifier(jwksDoc(served));

        // header kid=K1 resolves to the served public key, but the 64-byte sig was made by another
        // private key → well-formed yet cryptographically invalid.
        String forged = jwt("K1", attacker.priB64, validClaims("u"));
        assertVerifyCode(verify, forged, Status.Code.PERMISSION_DENIED,
                "well-formed 64-byte sig that fails verification ⇒ PERMISSION_DENIED (not UNAUTHENTICATED)");
    }

    // ------------------------------------------------------------------------------------------
    // 11. kid not in keyset ⇒ PERMISSION_DENIED.
    // ------------------------------------------------------------------------------------------
    @Test
    void unknownKid_isPermissionDenied() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        String wrongKid = jwt("no-such-kid", k1.priB64, validClaims("u"));
        assertVerifyCode(verify, wrongKid, Status.Code.PERMISSION_DENIED,
                "a kid absent from the JWKS ⇒ PERMISSION_DENIED");
    }

    // ------------------------------------------------------------------------------------------
    // 12. Rotation + revocation (O3): keyset REPLACE, not merge — the old kid stops verifying.
    // ------------------------------------------------------------------------------------------
    @Test
    void keyRotation_revokesOldKid_onReplace() throws Exception {
        Kp k1 = genKey("kid1");
        Kp k2 = genKey("kid2");

        JwsVerify verify = readyVerifier(jwksDoc(k1)); // JWKS: only K1
        String tokenK1 = jwt(k1, validClaims("u"));
        String tokenK2 = jwt(k2, validClaims("u"));
        assertVerifyOk(verify, tokenK1, "token signed by K1 verifies while K1 is served");

        // Rotate: server now serves ONLY K2. Force a synchronous rebuild.
        currentJwks = jwksDoc(k2);
        verify.loadJwks();

        assertVerifyOk(verify, tokenK2, "token signed by the rotated-in K2 verifies after reload");
        assertVerifyCode(verify, tokenK1, Status.Code.PERMISSION_DENIED,
                "K1 is REVOKED by map REPLACE (not merge) ⇒ its kid is now not found");
    }

    // ------------------------------------------------------------------------------------------
    // 13. Empty / null keys fail-closed (O-sec-47): loadJwks THROWS, no NPE, stays not-ready.
    // ------------------------------------------------------------------------------------------
    @Test
    void emptyKeys_failClosed_noNpe() throws Exception {
        startJwks("{\"keys\":[]}");
        JwsVerify verify = new JwsVerify(jwksUrl());

        assertThrows(RuntimeException.class, verify::loadJwks,
                "an empty keys array must throw (fail-closed), never quietly succeed");
        assertFalse(verify.isReady(), "empty keys must not flip ready=true");

        currentJwks = "{}"; // null keys
        assertThrows(RuntimeException.class, verify::loadJwks,
                "null keys must throw (no NPE on jwks.keys)");
        assertFalse(verify.isReady(), "null keys must not flip ready=true");

        assertVerifyCode(verify, jwt(genKey("K1"), validClaims("u")), Status.Code.UNAVAILABLE,
                "after empty/null-keys failures the verifier stays fail-closed (UNAVAILABLE)");
    }

    // ------------------------------------------------------------------------------------------
    // 14. Throttle window not burned (O4): a failed refetch keeps last-known-good and the 5-min window.
    // ------------------------------------------------------------------------------------------
    @Test
    void failedRefetch_keepsLastKnownGood_andPreservesOkWindow() throws Exception {
        Kp k1 = genKey("K1");
        Kp k2 = genKey("K2");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        String tokenK1 = jwt(k1, validClaims("u"));
        assertVerifyOk(verify, tokenK1, "K1 verifies while served");

        long okBefore = verify.lastOkFetch;
        jwksStatus = 500;               // IdP now blips
        verify.lastTryFetch = 1000L;    // age the try-window so maybeRefetch(0) definitely attempts

        verify.maybeRefetch(0);         // force one best-effort attempt; must swallow the 500

        assertEquals(okBefore, verify.lastOkFetch,
                () -> "lastOkFetch changed on a FAILED fetch (5-min window burned): " + verify.lastOkFetch);
        assertTrue(verify.lastTryFetch > 1000L,
                () -> "lastTryFetch did not advance on the attempt: " + verify.lastTryFetch);
        assertTrue(verify.isReady(), "a transient fetch failure must not reopen the fail-closed gate");
        assertVerifyOk(verify, tokenK1, "last-known-good keyset keeps serving through the 500");

        // Recover: window was never stuck, so a fresh key is picked up immediately on the next load.
        currentJwks = jwksDoc(k1, k2);
        jwksStatus = 200;
        verify.loadJwks();
        assertVerifyOk(verify, jwt(k2, validClaims("u")),
                "after recovery a newly-served K2 token verifies immediately (window not stuck)");
    }

    // ------------------------------------------------------------------------------------------
    // 15. B1 (fix-round-1): degenerate 64-byte signature (all-zero R or all-zero S) ⇒ UNAUTHENTICATED.
    // ------------------------------------------------------------------------------------------
    @Test
    void degenerateSignature_allZeroRorS_isUnauthenticated() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));

        // Positive control: the untampered canonical signature still verifies — the new degenerate
        // guards must not reject legitimate R||S signatures.
        String valid = jwt(k1, validClaims("u"));
        assertVerifyOk(verify, valid, "untampered token still verifies (guards don't break canonical sigs)");

        // R nonzero, S all zero: length 64 clears the length guard, then jws2der's S-scan reaches
        // k==0 (pre-fix it indexed one past the array → AIOOBE that escaped as UNKNOWN). Contract:
        // IllegalArgumentException → UNAUTHENTICATED.
        byte[] sZero = new byte[64];
        sZero[0] = 5;
        assertVerifyCode(verify, reSign(valid, Es256Signature.base64(sZero)),
                Status.Code.UNAUTHENTICATED,
                "all-zero-S 64-byte signature ⇒ UNAUTHENTICATED (regression: was UNKNOWN)");

        // R all zero, S nonzero: pre-fix a zero-length DER integer → SignatureException → PERMISSION_DENIED.
        // Contract: IllegalArgumentException → UNAUTHENTICATED.
        byte[] rZero = new byte[64];
        rZero[32] = 5;
        assertVerifyCode(verify, reSign(valid, Es256Signature.base64(rZero)),
                Status.Code.UNAUTHENTICATED,
                "all-zero-R 64-byte signature ⇒ UNAUTHENTICATED (regression: was PERMISSION_DENIED)");

        // Direct primitive guard (mirrors test 9's assertThrows): jws2der itself rejects both arrays.
        assertThrows(IllegalArgumentException.class, () -> Es256Jwk.jws2der(sZero),
                "jws2der must reject an all-zero-S concat");
        assertThrows(IllegalArgumentException.class, () -> Es256Jwk.jws2der(rZero),
                "jws2der must reject an all-zero-R concat");
    }

    // ------------------------------------------------------------------------------------------
    // 16. B2 (fix-round-1): valid-signature token whose header omits kid ⇒ UNAUTHENTICATED.
    // ------------------------------------------------------------------------------------------
    @Test
    void missingKid_isUnauthenticated() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));

        // Header JSON carries alg but NO kid → getKeyId() == null. Pre-fix: jwksCache.get(null) NPEs
        // (ConcurrentHashMap forbids null keys) → UNKNOWN. The signature is genuinely valid (signed
        // with k1 over the same header/payload), so only the missing-kid guard can reject it.
        String headerNoKid = b64("{\"alg\":\"ES256\"}");
        String token = new Es256Signature(k1.priB64).sign(headerNoKid, b64(validClaims("u")));

        assertVerifyCode(verify, token, Status.Code.UNAUTHENTICATED,
                "token missing kid ⇒ UNAUTHENTICATED (regression: was NPE → UNKNOWN)");
    }

    // ------------------------------------------------------------------------------------------
    // 17. B3 (fix-round-1): ready + zero-usable-keys refresh REVOKES (no throw, stays ready, denied).
    // ------------------------------------------------------------------------------------------
    @Test
    void revocation_emptyOrNullKeysRefresh_revokesButStaysReady() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        String token = jwt(k1, validClaims("u"));
        assertVerifyOk(verify, token, "baseline: token verifies while K1 is served");

        // Empty keys array on an ALREADY-ready verifier = full revocation. doFetch must REPLACE the
        // live map with an empty one and RETURN — a throw would be swallowed by maybeRefetch, leaving
        // the STALE keyset live and the revoked token still verifying (the bug this fixes).
        currentJwks = "{\"keys\":[]}";
        assertDoesNotThrow(verify::loadJwks,
                "empty-keys refresh on a ready verifier must NOT throw (else stale keys stay live)");
        assertTrue(verify.isReady(),
                "revocation keeps the verifier ready (fail-closed gate not reopened → NOT UNAVAILABLE)");
        assertVerifyCode(verify, token, Status.Code.PERMISSION_DENIED,
                "after empty-keys revocation the previously-valid token is denied (kid now missing)");

        // Same for null keys ({} → jwks.keys == null): no NPE, no throw, still ready, still revoked.
        currentJwks = "{}";
        assertDoesNotThrow(verify::loadJwks,
                "null-keys refresh on a ready verifier must NOT throw (no NPE on jwks.keys)");
        assertTrue(verify.isReady(), "null-keys revocation keeps the verifier ready");
        assertVerifyCode(verify, token, Status.Code.PERMISSION_DENIED,
                "after null-keys revocation the previously-valid token is denied");
    }

    // ------------------------------------------------------------------------------------------
    // 18. advisory (fix-round-1): aud may be a single JSON string (RFC 7519), not only an array.
    // ------------------------------------------------------------------------------------------
    @Test
    void singleStringAudience_acceptedOrCleanlyRejected() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1)).requiredAudiences(List.of("api-x"));
        long exp = nowSec() + 3600;

        // Matching single-STRING aud ⇒ OK. Pre-fix: (List) cast on a String → ClassCastException → UNKNOWN.
        assertVerifyOk(verify, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"aud\":\"api-x\"}"),
                "single-string aud matching requiredAudiences ⇒ OK (regression: was CCE → UNKNOWN)");

        // Non-matching single-STRING aud ⇒ clean UNAUTHENTICATED (not UNKNOWN).
        assertVerifyCode(verify, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"aud\":\"other\"}"),
                Status.Code.UNAUTHENTICATED,
                "single-string aud disjoint from requiredAudiences ⇒ UNAUTHENTICATED");
    }

    // ------------------------------------------------------------------------------------------
    // 19. advisory (fix-round-1): a slow-drip body is bounded by bodyReadTimeoutMillis — loadJwks
    //     throws at the read deadline (not the 2s stall / 10s request timeout) and stays fail-closed.
    // ------------------------------------------------------------------------------------------
    @Test
    void stalledBody_boundedByReadTimeout_failsClosed() throws Exception {
        // Inline server (NOT the shared startJwks handler, which writes immediately): promise 1000
        // bytes, deliver only a few, then sleep 2s so the client's readNBytes blocks mid-body.
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext(JWKS_PATH, ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 1000);
            try (OutputStream os = ex.getResponseBody()) {
                os.write("{\"keys\"".getBytes(UTF_8));
                os.flush();
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
        slow.start();
        try {
            String url = "http://127.0.0.1:" + slow.getAddress().getPort() + JWKS_PATH;
            JwsVerify verify = new JwsVerify(url);
            verify.bodyReadTimeoutMillis = 300;

            long start = System.nanoTime();
            assertThrows(RuntimeException.class, verify::loadJwks,
                    "a stalled response body must make loadJwks throw at the read deadline");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(elapsedMs < 1500,
                    () -> "loadJwks must return at the ~300ms read deadline, not the 2s stall / 10s "
                            + "request timeout; elapsed=" + elapsedMs + "ms");
            assertFalse(verify.isReady(), "a stalled fetch must leave the verifier fail-closed (not ready)");
        } finally {
            slow.stop(0);
        }
    }

    // ==========================================================================================
    // Harness (inlined from GrpcContextAuthIT — do NOT import it).
    // ==========================================================================================

    /** An EC P-256 keypair plus the url-base64 PKCS8 private key the production signer wants. */
    record Kp(String kid, String priB64, ECPublicKey pub) {}

    private static Kp genKey(String kid) throws Exception {
        var kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        String priB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(kp.getPrivate().getEncoded());
        return new Kp(kid, priB64, (ECPublicKey) kp.getPublic());
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String validClaims(String sub) {
        return "{\"sub\":\"" + sub + "\",\"exp\":" + (nowSec() + 3600) + "}";
    }

    private static String b64(String s) {
        return Es256Signature.base64(s.getBytes(UTF_8));
    }

    private static String header64(String kid) {
        return b64("{\"kid\":\"" + kid + "\",\"alg\":\"ES256\"}");
    }

    /** Sign an ES256 JWT with an explicit kid + private key over an arbitrary claims JSON. */
    private static String jwt(String kid, String priB64, String claimsJson) {
        return new Es256Signature(priB64).sign(header64(kid), b64(claimsJson));
    }

    private static String jwt(Kp kp, String claimsJson) {
        return jwt(kp.kid, kp.priB64, claimsJson);
    }

    /** Sign over a RAW (already-encoded, possibly invalid-base64) payload segment. */
    private static String jwtRawPayload(Kp kp, String rawPayloadSegment) {
        return new Es256Signature(kp.priB64).sign(header64(kp.kid), rawPayloadSegment);
    }

    /** The base64url signature segment (after the last dot). */
    private static String sigOf(String token) {
        return token.substring(token.lastIndexOf('.') + 1);
    }

    /** Rebuild a token keeping header.payload but swapping in a new signature segment. */
    private static String reSign(String token, String newSig64) {
        return token.substring(0, token.lastIndexOf('.') + 1) + newSig64;
    }

    /** Flip one interior base64url char (length preserved → still decodes to the same byte count). */
    private static String flipOneChar(String s) {
        char[] c = s.toCharArray();
        int i = c.length / 2;
        c[i] = (c[i] == 'A') ? 'B' : 'A';
        return new String(c);
    }

    private static String jwksDoc(Kp... keys) {
        var sb = new StringBuilder("{\"keys\":[");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(jwkEntry(keys[i]));
        }
        return sb.append("]}").toString();
    }

    private static String jwkEntry(Kp kp) {
        var w = kp.pub.getW();
        return "{\"kty\":\"EC\",\"use\":\"sig\",\"crv\":\"P-256\",\"kid\":\"" + kp.kid
                + "\",\"x\":\"" + coord(w.getAffineX()) + "\",\"y\":\"" + coord(w.getAffineY()) + "\"}";
    }

    /** Fixed 32-byte (P-256) url-base64 of an affine coordinate, per JWK spec. */
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

    /** Start the loopback JWKS server serving {@code body} with 200; records it for teardown. */
    private void startJwks(String body) throws Exception {
        currentJwks = body;
        jwksStatus = 200;
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext(JWKS_PATH, ex -> {
            byte[] out = currentJwks.getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(jwksStatus, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        jwksServer.start();
    }

    private String jwksUrl() {
        return "http://127.0.0.1:" + jwksServer.getAddress().getPort() + JWKS_PATH;
    }

    /** Start the server serving {@code body} and return a verifier already loaded (ready). */
    private JwsVerify readyVerifier(String body) throws Exception {
        startJwks(body);
        JwsVerify v = new JwsVerify(jwksUrl());
        v.loadJwks();
        return v;
    }

    /** A URL to a port that was open then closed — connections are refused. */
    private static String refusedUrl() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        return "http://127.0.0.1:" + port + "/";
    }

    // --- exact-code assertion helpers ---------------------------------------------------------

    private static void assertVerifyCode(JwsVerify v, String token, Status.Code expected, String label) {
        StatusException ex = assertThrows(StatusException.class,
                () -> v.verify(token, CID, false),
                () -> label + " → expected " + expected + " but no StatusException was thrown");
        assertEquals(expected, ex.getStatus().getCode(),
                () -> label + " → expected " + expected + " but was " + ex.getStatus().getCode()
                        + " (\"" + ex.getStatus().getDescription() + "\")");
    }

    private static UserCredential assertVerifyOk(JwsVerify v, String token, String label) {
        try {
            UserCredential c = v.verify(token, CID, false);
            assertNotNull(c, () -> label + " → expected a credential, got null");
            return c;
        } catch (StatusException e) {
            throw new AssertionError(label + " → expected OK but was rejected: "
                    + e.getStatus().getCode() + " (\"" + e.getStatus().getDescription() + "\")", e);
        }
    }
}
