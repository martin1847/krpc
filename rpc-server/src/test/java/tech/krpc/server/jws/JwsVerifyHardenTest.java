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
import tech.krpc.util.JsonUtils;

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
    // 13b. Per-JWK tolerance (1.2.0): one unusable entry SKIPS, it never fails the whole keyset.
    //
    // RFC 7517 §4 puts no type constraint on JWK members, and a real keyset mixes key types this
    // verifier does not implement. Before 1.2.0 `Jwks.keys` was List<Map<String,String>>, so a
    // member carrying a non-string leaned on Jackson silently stringifying it; under the strict
    // decoding that is now the default that would have failed the ENTIRE document. Failing the
    // document is the expensive outcome: a thrown load is swallowed by maybeRefetch(), stranding
    // the last-known-good keyset at refresh time or blocking bootstrap outright. These two tests
    // pin the tolerance at the JWK level, and — just as important — that a GOOD key sharing the
    // document still loads and still verifies.
    // ------------------------------------------------------------------------------------------
    @Test
    void jwkMissingCoordinates_isSkipped_goodKeyInSameDocumentStillLoads() throws Exception {
        Kp good = genKey("GOOD");
        // An EC entry with no x/y. Pre-1.2.0 this reached Es256Jwk with nulls and blew up inside
        // Base64.decode(null), failing the whole fetch and taking the good key down with it.
        String broken = "{\"kty\":\"EC\",\"use\":\"sig\",\"crv\":\"P-256\",\"kid\":\"BROKEN\"}";
        String doc = "{\"keys\":[" + broken + "," + jwkEntry(good) + "]}";

        JwsVerify verify = readyVerifier(doc);

        assertTrue(verify.isReady(), "one unusable JWK must not fail the fetch");
        assertEquals(java.util.Set.of("GOOD"), verify.jwksCache.keySet(),
                "the unusable JWK is skipped; the good one is loaded");
        assertVerifyOk(verify, jwt(good, validClaims("u")),
                "a token signed by the good key in a partially-broken document still verifies");
    }

    @Test
    void jwkWithNonStringMember_isSkipped_goodKeyInSameDocumentStillLoads() throws Exception {
        Kp good = genKey("GOOD");
        // A numeric kid — the shape of a vendor that emits integer key ids. Legal JSON, legal JWK
        // per RFC 7517, unusable here (this verifier keys the cache by a String kid).
        String numericKid = "{\"kty\":\"EC\",\"use\":\"sig\",\"crv\":\"P-256\",\"kid\":42,"
                + "\"x\":\"RhdqCsq1MooCjBiOrliZInAii8fdf4-3jOT0pRpohus\","
                + "\"y\":\"6s9ECrJurlHCkSx8CTnqhS5HN7h9-dblFgLfpRPcPeg\"}";
        String doc = "{\"keys\":[" + numericKid + "," + jwkEntry(good) + "]}";

        JwsVerify verify = readyVerifier(doc);

        assertTrue(verify.isReady(), "a non-string JWK member must not fail the fetch");
        assertEquals(java.util.Set.of("GOOD"), verify.jwksCache.keySet(),
                "the JWK with a numeric kid is skipped; the good one is loaded");
        assertVerifyOk(verify, jwt(good, validClaims("u")),
                "a token signed by the good key still verifies alongside a skipped vendor entry");
    }

    /**
     * THE ONE THAT MATTERS: a JWK whose members are all well-formed STRINGS but whose key material
     * is garbage. Member-type checks do not catch this — only building the key does — so before the
     * fix {@code toECPublicKey()} threw straight out of the loop and killed the whole refresh.
     *
     * <p>Why that is a security bug and not a robustness nit: the throw is swallowed by
     * {@code maybeRefetch()}, leaving the PREVIOUS cache live. An IdP that revokes a compromised
     * key, publishes its replacement, and also happens to serve one malformed entry would keep the
     * revoked key verifying signatures for as long as the bad entry stays in the document.
     */
    @Test
    void jwkWithUnusableKeyMaterial_isSkipped_goodKeyStillLoads_andRotationStillTakesEffect() throws Exception {
        Kp good = genKey("NEW");
        // Well-formed strings; the coordinates are not valid base64url key material.
        String badMaterial = "{\"kty\":\"EC\",\"use\":\"sig\",\"crv\":\"P-256\",\"kid\":\"BAD\","
                + "\"x\":\"!!!not-base64!!!\",\"y\":\"@@@also-not-base64@@@\"}";
        String doc = "{\"keys\":[" + badMaterial + "," + jwkEntry(good) + "]}";

        JwsVerify verify = readyVerifier(doc);

        assertTrue(verify.isReady(), "unusable key MATERIAL must not fail the fetch");
        assertEquals(java.util.Set.of("NEW"), verify.jwksCache.keySet(),
                "the JWK with bad key material is skipped; the good one is loaded");
        assertVerifyOk(verify, jwt(good, validClaims("u")),
                "a token signed by the good key verifies despite a sibling with bad key material");
    }

    /**
     * The rotation scenario end to end: a REVOKED key must stop verifying even when the replacement
     * document also carries an unbuildable entry. Pre-fix the refresh threw, the old cache survived,
     * and the revoked key kept working — this is the regression that test guards.
     */
    @Test
    void revokedKeyStopsVerifying_evenWhenTheNewDocumentCarriesABadEntry() throws Exception {
        Kp revoked = genKey("OLD");
        Kp replacement = genKey("NEW");
        String revokedToken = jwt(revoked, validClaims("u"));

        JwsVerify verify = readyVerifier(jwksDoc(revoked));
        assertVerifyOk(verify, revokedToken, "precondition: the old key verifies before rotation");

        // Rotation: OLD withdrawn, NEW published, plus one entry that cannot be built.
        String badMaterial = "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"BAD\","
                + "\"x\":\"!!!\",\"y\":\"!!!\"}";
        currentJwks = "{\"keys\":[" + badMaterial + "," + jwkEntry(replacement) + "]}";
        verify.loadJwks();

        assertEquals(java.util.Set.of("NEW"), verify.jwksCache.keySet(),
                "rotation must take effect despite the unbuildable entry");
        assertVerifyCode(verify, revokedToken, Status.Code.PERMISSION_DENIED,
                "the REVOKED key must no longer verify — a swallowed refresh here is the security bug");
        assertVerifyOk(verify, jwt(replacement, validClaims("u")), "the replacement key verifies");
    }

    /** A blank kid cannot ever authenticate anything, so it must not inflate the keyset. */
    @Test
    void jwkWithBlankKid_isSkipped_notCachedAsAUsableKey() throws Exception {
        Kp good = genKey("GOOD");
        var w = genKey("ignored").pub().getW();
        String blankKid = "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"   \",\"x\":\""
                + coord(w.getAffineX()) + "\",\"y\":\"" + coord(w.getAffineY()) + "\"}";

        JwsVerify verify = readyVerifier("{\"keys\":[" + blankKid + "," + jwkEntry(good) + "]}");

        assertEquals(java.util.Set.of("GOOD"), verify.jwksCache.keySet(),
                "a blank kid is unusable (verify() rejects blank kids) and must not be cached");
    }

    @Test
    void keysetOfOnlyUnusableJwks_isTreatedAsZeroKeys_notAsAParseFailure() throws Exception {
        // All entries skipped -> the same "no usable keys" path as an empty array: at bootstrap that
        // is fail-closed + not ready. The point is that it gets there by SKIPPING, so the assertions
        // below have to separate that from a decode failure or a construction failure, which would
        // ALSO throw and ALSO leave ready=false.
        String doc = "{\"keys\":["
                + "{\"kty\":\"EC\",\"kid\":\"NO_COORDS\"},"                       // missing members
                + "{\"kty\":\"EC\",\"kid\":42,\"x\":\"A\",\"y\":\"B\",\"crv\":\"P-256\"},"  // non-string member
                + "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"BAD\",\"x\":\"!!\",\"y\":\"!!\"},"  // bad material
                + "{\"kty\":\"RSA\",\"kid\":\"R1\",\"n\":1}"                     // unimplemented type
                + "]}";
        startJwks(doc);
        JwsVerify verify = new JwsVerify(jwksUrl());

        // (a) The document itself DECODES — this is the half the previous version of this test
        //     could not distinguish. If strict decoding or a skip regression broke parsing, this
        //     line fails with JsonDecodeException instead of reaching the zero-key path.
        assertDoesNotThrow(() -> JsonUtils.parse(currentJwks, Jwks.class),
                "every entry above is legal JSON: the document must parse, then skip entry by entry");

        // (b) Having skipped all four, we land on the zero-key revocation signal — the SAME
        //     exception the empty-array case produces, with the same message.
        RuntimeException ex = assertThrows(RuntimeException.class, verify::loadJwks,
                "a keyset with no USABLE keys is the zero-key revocation path (fail-closed)");
        assertEquals("jwks has no usable keys", ex.getMessage(),
                "must be the zero-key signal, NOT a decode/construction failure leaking out");

        // (c) Fail-closed, and nothing was cached along the way.
        assertFalse(verify.isReady(), "no usable keys must not flip ready=true");
        assertTrue(verify.jwksCache.isEmpty(), "no partial key may survive an all-unusable document");
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

        // codex-r2 test gap: a BLANK (whitespace-only) kid must also reject. Header carries a valid
        // signature, so only kid.isBlank() in the guard can turn it away — kill that clause and this
        // token would reach jwksCache.get("   ") → PERMISSION_DENIED, i.e. the wrong code. This pins
        // the guard to isBlank(), not just != null.
        String headerBlankKid = b64("{\"kid\":\"   \",\"alg\":\"ES256\"}");
        String blankKidToken = new Es256Signature(k1.priB64).sign(headerBlankKid, b64(validClaims("u")));
        assertVerifyCode(verify, blankKidToken, Status.Code.UNAUTHENTICATED,
                "token with blank kid ⇒ UNAUTHENTICATED (pins kid.isBlank() guard, not just != null)");
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

    // ------------------------------------------------------------------------------------------
    // 20. codex-r2 (blocking): valid signature + wrong-TYPE temporal/binding claim ⇒ UNAUTHENTICATED.
    //     {"exp":"x"} / {"nbf":[...]} / {"chl":"x"} deserialize a String/List where a Number is
    //     required; the (Number) casts threw ClassCastException that escaped verify() as UNKNOWN.
    // ------------------------------------------------------------------------------------------
    @Test
    void malformedClaimTypes_mapToUnauthenticated_notUnknown() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        long exp = nowSec() + 3600;

        // Each token is signed correctly (valid 64-byte sig, known kid) so it CLEARS the signature
        // check and reaches the claim reads — isolating the claim-type fault. RED LINE: every one
        // must be a clean UNAUTHENTICATED, never a wrapped CCE → UNKNOWN.

        // exp as a STRING → (Number) getExpiresAt() throws before the null check.
        assertVerifyCode(verify, jwt(k1, "{\"sub\":\"u\",\"exp\":\"not-a-number\"}"),
                Status.Code.UNAUTHENTICATED,
                "string exp ⇒ UNAUTHENTICATED (regression: was CCE → UNKNOWN)");
        // exp as an ARRAY → same cast, same escape.
        assertVerifyCode(verify, jwt(k1, "{\"sub\":\"u\",\"exp\":[1,2,3]}"),
                Status.Code.UNAUTHENTICATED,
                "array exp ⇒ UNAUTHENTICATED (regression: was CCE → UNKNOWN)");
        // nbf as a STRING (exp valid so we reach nbf) → (Number) getNotBefore() CCE.
        assertVerifyCode(verify, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"nbf\":\"soon\"}"),
                Status.Code.UNAUTHENTICATED,
                "string nbf ⇒ UNAUTHENTICATED (regression: was CCE → UNKNOWN)");
        // nbf as an ARRAY → same.
        assertVerifyCode(verify, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"nbf\":[1]}"),
                Status.Code.UNAUTHENTICATED,
                "array nbf ⇒ UNAUTHENTICATED (regression: was CCE → UNKNOWN)");

        // chl is read ONLY when bindClient=true → (Number) getClientHashLong() CCE. readyVerifier is
        // bindClient=false, so build a BOUND verifier over the SAME running JWKS.
        JwsVerify bound =
                new JwsVerify(verify.getUrl(), JwsVerify.DEFAULT_COOKIE_NAME, ExtVerify.EMPTY, true);
        bound.loadJwks();
        assertVerifyCode(bound, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"chl\":\"nope\"}"),
                Status.Code.UNAUTHENTICATED,
                "string chl (bindClient) ⇒ UNAUTHENTICATED (regression: was CCE → UNKNOWN)");
    }

    // ------------------------------------------------------------------------------------------
    // 21. HARDEN-B1 fix-round-3 (STRUCTURAL closeout): a CUSTOM ExtVerify reading iat must not let
    //     a malformed iat escape as UNKNOWN. Pre-fix extVerify.afterSignCheck ran AFTER the claim
    //     catch closed, so getIssuedAt()'s raw (Number) cast (CCE on string/array/object) or a
    //     null iat (.longValue() NPE on missing/null) escaped verify() as UNKNOWN. The fix widens
    //     the catch to ENCLOSE extVerify. INVARIANT GUARD: this test also pins that structure —
    //     move extVerify.afterSignCheck back OUT of the catch and every malformed case below turns
    //     UNKNOWN → RED. It closes the whole class of "future ExtVerify reads claim X" escapes, not
    //     just iat: any claim read inside afterSignCheck now fails CLOSED as UNAUTHENTICATED.
    // ------------------------------------------------------------------------------------------
    @Test
    void customExtVerifyReadingIat_malformedIat_mapsToUnauthenticated_notUnknown() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        long exp = nowSec() + 3600;

        // A custom ExtVerify that READS iat and USES it: getIssuedAt() throws CCE on a non-Number
        // iat (string/array/object); a missing/null iat returns null → .longValue() NPEs. Both are
        // RuntimeExceptions that verify() MUST catch and remap to UNAUTHENTICATED — never UNKNOWN.
        ExtVerify iatReader = (jws, isCookie) -> {
            Number iat = jws.getIssuedAt();
            if (iat.longValue() < 0) {
                throw Status.UNAUTHENTICATED.withDescription("negative iat").asException();
            }
        };
        JwsVerify ext = new JwsVerify(verify.getUrl(), JwsVerify.DEFAULT_COOKIE_NAME, iatReader, false);
        ext.loadJwks();

        // Positive control: a well-formed NUMERIC iat runs the ExtVerify to completion and PASSES —
        // proves the ExtVerify actually executes (the fix didn't just swallow it) and a legitimate
        // token is not collateral damage of the widened catch.
        assertVerifyOk(ext, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"iat\":" + nowSec() + "}"),
                "numeric iat runs the custom ExtVerify and passes (proves ext actually executes)");

        // string / array / object iat → getIssuedAt()'s (Number) cast throws CCE inside extVerify.
        assertVerifyCode(ext, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"iat\":\"nope\"}"),
                Status.Code.UNAUTHENTICATED,
                "string iat read by custom ExtVerify ⇒ UNAUTHENTICATED (regression: was CCE → UNKNOWN)");
        assertVerifyCode(ext, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"iat\":[1,2,3]}"),
                Status.Code.UNAUTHENTICATED,
                "array iat read by custom ExtVerify ⇒ UNAUTHENTICATED (regression: was CCE → UNKNOWN)");
        assertVerifyCode(ext, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"iat\":{\"x\":1}}"),
                Status.Code.UNAUTHENTICATED,
                "object iat read by custom ExtVerify ⇒ UNAUTHENTICATED (regression: was CCE → UNKNOWN)");
        // missing / null iat → getIssuedAt() returns null → .longValue() NPE inside extVerify.
        assertVerifyCode(ext, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + "}"),
                Status.Code.UNAUTHENTICATED,
                "missing iat read by custom ExtVerify ⇒ UNAUTHENTICATED (regression: was NPE → UNKNOWN)");
        assertVerifyCode(ext, jwt(k1, "{\"sub\":\"u\",\"exp\":" + exp + ",\"iat\":null}"),
                Status.Code.UNAUTHENTICATED,
                "null iat read by custom ExtVerify ⇒ UNAUTHENTICATED (regression: was NPE → UNKNOWN)");
    }

    // ------------------------------------------------------------------------------------------
    // 22. HARDEN-B1 fix-round-4 (alg conformance / defense-in-depth): the header `alg` MUST be
    //     exactly "ES256". NOTE the framing — this is NOT a fail-open fix. The server always
    //     verifies with hardcoded SHA256withECDSA over EC-only keys and ignores the client alg, so
    //     alg-confusion (alg=none, RS256-with-EC-key) is already structurally impossible; a token
    //     can only pass with a real ES256 signature. This guard adds RFC 7515 §4.1.1 conformance:
    //     alg none / RS256 / missing / non-string ⇒ UNAUTHENTICATED, up front — even when the
    //     signature bytes are a perfectly valid ES256 signature (rejected for alg, not for sig).
    // ------------------------------------------------------------------------------------------
    @Test
    void algConformance_nonEs256HeaderRejected_evenWithValidSignature() throws Exception {
        Kp k1 = genKey("K1");
        JwsVerify verify = readyVerifier(jwksDoc(k1));
        String claims = validClaims("u");

        // Positive control: the correct alg=ES256 still verifies — the guard must not reject
        // legitimate tokens (and proves these tokens are otherwise fully valid, so any rejection
        // below is due to alg alone).
        assertVerifyOk(verify, jwtHeader(k1, "{\"kid\":\"K1\",\"alg\":\"ES256\"}", claims),
                "alg=ES256 with a valid signature still verifies (guard doesn't break canonical tokens)");

        // Each token below carries a genuinely valid ES256 signature over its (bad-alg) header, so
        // only the alg guard can turn it away — proving alg is checked independently of the crypto.
        assertVerifyCode(verify, jwtHeader(k1, "{\"kid\":\"K1\",\"alg\":\"none\"}", claims),
                Status.Code.UNAUTHENTICATED,
                "alg=none ⇒ UNAUTHENTICATED (even though the ES256 signature is valid)");
        assertVerifyCode(verify, jwtHeader(k1, "{\"kid\":\"K1\",\"alg\":\"RS256\"}", claims),
                Status.Code.UNAUTHENTICATED,
                "alg=RS256 ⇒ UNAUTHENTICATED (alg-confusion attempt turned away by conformance guard)");
        assertVerifyCode(verify, jwtHeader(k1, "{\"kid\":\"K1\"}", claims),
                Status.Code.UNAUTHENTICATED,
                "missing alg ⇒ UNAUTHENTICATED (RFC 7515 §4.1.1 requires alg)");
        // Non-string alg: header parses as Map<String,String>, so getAlgorithm()'s String checkcast
        // throws CCE — caught as malformed (not escaping as UNKNOWN), still UNAUTHENTICATED.
        assertVerifyCode(verify, jwtHeader(k1, "{\"kid\":\"K1\",\"alg\":256}", claims),
                Status.Code.UNAUTHENTICATED,
                "numeric alg ⇒ UNAUTHENTICATED (non-string alg: CCE caught as malformed, not UNKNOWN)");
        assertVerifyCode(verify, jwtHeader(k1, "{\"kid\":\"K1\",\"alg\":[\"ES256\"]}", claims),
                Status.Code.UNAUTHENTICATED,
                "array alg ⇒ UNAUTHENTICATED (non-string alg: CCE caught as malformed, not UNKNOWN)");
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

    /**
     * Sign a JWT over an ARBITRARY header JSON (so tests can inject a bad/missing/non-string alg).
     * The signature is a genuine ES256 signature over this exact header+payload, so any rejection is
     * attributable to the alg-conformance guard, not to a signature mismatch.
     */
    private static String jwtHeader(Kp kp, String headerJson, String claimsJson) {
        return new Es256Signature(kp.priB64).sign(b64(headerJson), b64(claimsJson));
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
