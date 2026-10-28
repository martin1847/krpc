package tech.krpc.server.jws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import tech.krpc.util.JsonDecodeException;
import tech.krpc.util.JsonUtils;

/**
 * {@link Jwks} must decode under the strict default (1.2.0) without any lenient escape hatch.
 *
 * <p>The reason this class exists: {@code keys} used to be typed {@code List<Map<String,String>>},
 * which quietly depended on Jackson stringifying whatever a provider put in a JWK member. RFC 7517
 * §4 places no type constraint on members, so a vendor extension carrying a number, boolean, array
 * or object is a legal JWKS document — and under strict decoding the old typing would have failed
 * the ENTIRE keyset over one such member. A failed refresh strands the last-known-good keys
 * (rotation and revocation stop propagating) and a failed bootstrap leaves auth unavailable, so the
 * fix is the DTO shape ({@code Map<String,Object>}), not a lenient decode.
 */
class JwksStrictDecodeTest {

    /** A realistic keyset: one usable EC key plus vendor extensions of every non-string JSON type. */
    private static final String VENDOR_EXTENDED_JWKS = """
            {"keys":[{
              "kty":"EC","use":"sig","crv":"P-256","kid":"bo-test-2111",
              "x":"RhdqCsq1MooCjBiOrliZInAii8fdf4-3jOT0pRpohus",
              "y":"6s9ECrJurlHCkSx8CTnqhS5HN7h9-dblFgLfpRPcPeg",
              "vendorExt":7, "vendorFlag":true, "exp":1730000000,
              "x5c":["MIIB"], "meta":{"rotated":false}
            }]}""";

    /**
     * The regression: this document decodes cleanly under the strict default. Before the DTO fix
     * the {@code vendorExt}/{@code vendorFlag} numbers and booleans were coerced into
     * {@code String}, which strict decoding turns into a whole-keyset failure.
     */
    @Test
    void vendorExtendedKeyset_decodesUnderStrictDefault() {
        var jwks = assertDoesNotThrow(() -> JsonUtils.parse(VENDOR_EXTENDED_JWKS, Jwks.class));
        assertEquals(1, jwks.getKeys().size());

        var jwk = jwks.getKeys().get(0);
        // The consumed members are still strings and still readable.
        assertEquals("EC", jwk.get(Jwks.KEY_TYPE));
        assertEquals("bo-test-2111", jwk.get(PublicClaims.KEY_ID));
        assertEquals("P-256", jwk.get("crv"));
        // The extensions kept their real JSON types instead of being stringified.
        assertEquals(Integer.valueOf(7), jwk.get("vendorExt"));
        assertEquals(Boolean.TRUE, jwk.get("vendorFlag"));
    }

    /**
     * A JWK whose consumed member is the wrong JSON type still decodes — it is
     * {@code JwsVerify.loadJwks} that skips such an entry, so the document must survive parsing to
     * give it that chance. Guards against anyone re-tightening the DTO.
     */
    @Test
    void jwkWithNonStringConsumedMember_stillDecodes() {
        var json = "{\"keys\":[{\"kty\":\"EC\",\"kid\":42,\"x\":\"A\",\"y\":\"B\",\"crv\":\"P-256\"}]}";
        var jwks = assertDoesNotThrow(() -> JsonUtils.parse(json, Jwks.class));
        assertEquals(Integer.valueOf(42), jwks.getKeys().get(0).get("kid"));
    }

    /** An empty or absent keys array is a valid document (the revocation signal), not a parse error. */
    @Test
    void emptyOrAbsentKeys_decode() {
        assertEquals(0, JsonUtils.parse("{\"keys\":[]}", Jwks.class).getKeys().size());
        assertNull(JsonUtils.parse("{}", Jwks.class).getKeys());
    }

    /** Genuinely malformed JSON is still a decode failure — the strict default did not soften that. */
    @Test
    void malformedDocument_stillFails() {
        assertThrows(JsonDecodeException.class, () -> JsonUtils.parse("{ not json", Jwks.class));
    }
}
