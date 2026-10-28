package tech.krpc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * OTEL-002 Fix 3: the shared credential redactor. Policy is MASK-BY-DEFAULT (R1-2): every header
 * value is masked unless its name is on the diagnostic-safe allow-list; {@code cookie} keeps names
 * but masks all values including bare segments (R1-3).
 */
class LogRedactTest {

    private static final String JWT =
            "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ1c2VyLTQyIn0.c2lnbmF0dXJlLWJ5dGVz";

    // --- default-deny: anything not allow-listed is masked -----------------------------------

    @Test
    void authorizationBearerIsMasked() {
        String out = LogRedact.value("Authorization", "Bearer " + JWT);
        assertEquals(LogRedact.MASK, out);
        assertFalse(out.contains(JWT));
    }

    @Test
    void unlistedTokenShapedHeadersAreMasked() {
        // R1-2 negative probes: names not on the allow-list must be masked, not passed through.
        for (String name : new String[]{"X-Token", "X-Access-Token", "X-Goog-Api-Key",
                "X-Api-Key", "X-Auth-Token", "Proxy-Authorization", "X-Some-Vendor-Secret"}) {
            String out = LogRedact.value(name, JWT);
            assertEquals(LogRedact.MASK, out, () -> name + " must be masked by default");
            assertFalse(out.contains(JWT));
        }
    }

    // --- allow-list: diagnostic-safe headers pass through ------------------------------------

    @Test
    void diagnosticSafeHeadersPassThrough() {
        assertEquals("application/json", LogRedact.value("Content-Type", "application/json"));
        assertEquals("curl/8.0", LogRedact.value("User-Agent", "curl/8.0"));
        assertEquals("00-abc-def-01", LogRedact.value("traceparent", "00-abc-def-01"));
        assertEquals("req-123", LogRedact.value("X-Request-Id", "req-123"));
        assertTrue(LogRedact.isDiagnosticSafe("HOST"));
        assertFalse(LogRedact.isDiagnosticSafe("authorization"));
        assertFalse(LogRedact.isDiagnosticSafe("x-token"));
    }

    // --- cookie: names kept, all values masked (R1-3) ----------------------------------------

    @Test
    void cookieKeepsNamesMasksValues_includingAccessToken() {
        String out = LogRedact.value("Cookie", "access-token=" + JWT + "; theme=dark");
        assertEquals("access-token=" + LogRedact.MASK + "; theme=" + LogRedact.MASK, out);
        assertFalse(out.contains(JWT), "the access-token JWT must not survive");
        assertTrue(out.contains("access-token="), "cookie names are kept for debuggability");
    }

    @Test
    void bareCookieSegmentIsMasked() {
        // R1-3: a bare (=-less) JWT-shaped segment could itself be the secret — mask it whole.
        String out = LogRedact.value("cookie", JWT + "; theme=dark");
        assertFalse(out.contains(JWT), () -> "bare secret segment leaked: " + out);
        assertEquals(LogRedact.MASK + "; theme=" + LogRedact.MASK, out);
    }

    @Test
    void bareCookieSegmentTrailing_isMasked() {
        String out = LogRedact.value("cookie", "access-token=" + JWT + "; " + JWT);
        assertFalse(out.contains(JWT), () -> "trailing bare secret leaked: " + out);
        assertEquals("access-token=" + LogRedact.MASK + "; " + LogRedact.MASK, out);
    }

    // --- edges --------------------------------------------------------------------------------

    @Test
    void caseInsensitiveMatching() {
        assertEquals(LogRedact.MASK, LogRedact.value("AUTHORIZATION", "Bearer x"));
        assertEquals("text/plain", LogRedact.value("CONTENT-TYPE", "text/plain"));
        assertTrue(LogRedact.maskCookie("Access-Token=" + JWT).startsWith("Access-Token="));
        assertFalse(LogRedact.value("COOKIE", "access-token=" + JWT).contains(JWT));
    }

    @Test
    void nullValueStaysNull() {
        assertEquals(null, LogRedact.value("Authorization", null));
    }
}
