package tech.krpc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * HARDEN-B3 fix #8 — {@link JsonUtils#parse} raises a typed, message-SANITIZED
 * {@link JsonDecodeException} on malformed JSON.
 *
 * <p>Pre-fix it rethrew Jackson's {@code JsonProcessingException} as a bare {@code RuntimeException}
 * whose message carried Jackson internals (field/class names, source offsets) — which escaped to the
 * client as {@code UNKNOWN}/500. The contract now: a neutral client-safe message, the raw Jackson
 * exception retained ONLY as the cause (for server-side logs).
 */
class JsonDecodeTest {

    private static final String SANITIZED = "malformed JSON: cannot decode request body";

    @Test
    void malformedJson_throwsSanitizedJsonDecodeException() {
        var ex = assertThrows(JsonDecodeException.class,
                () -> JsonUtils.parse("{ not json", HashMap.class));

        assertEquals(SANITIZED, ex.getMessage());
        // Never leak Jackson internals nor the target type to the client.
        assertFalse(ex.getMessage().contains("com.fasterxml"),
                () -> "message leaked Jackson internals: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(HashMap.class.getSimpleName()),
                () -> "message leaked the target type: " + ex.getMessage());
        // The raw Jackson exception is kept ONLY as the cause, for server-side logs. Jackson is an
        // `implementation` dep of rpc-common — on the test RUNTIME classpath but not the test COMPILE
        // classpath — so assert the cause's type reflectively (a real isInstance check) rather than
        // importing the unavailable class.
        Throwable cause = ex.getCause();
        assertNotNull(cause, "the raw Jackson exception must be retained as the cause");
        assertTrue(isJsonProcessingException(cause),
                () -> "cause must be a Jackson JsonProcessingException, was: " + cause.getClass().getName());
    }

    private static boolean isJsonProcessingException(Throwable t) {
        try {
            Class<?> jpe = Class.forName("com.fasterxml.jackson.core.JsonProcessingException");
            return jpe.isInstance(t);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Jackson missing from the test runtime classpath", e);
        }
    }

    @Test
    void wellFormedJson_parsesNormally() {
        Map<?, ?> parsed = JsonUtils.parse("{\"a\":1}", HashMap.class);
        assertEquals(1, parsed.get("a"));
    }
}
