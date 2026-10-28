package tech.krpc.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract for strict JSON decoding and its {@code KRPC_JSON_STRICT} kill switch.
 *
 * <p>Since 1.2.0 strict decoding is the DEFAULT: a JSON number or boolean into a {@code String}
 * target is a decode failure, not a silent stringification. The env var only ever weakens that.
 *
 * <p>{@code JsonUtils} keeps two immutable mappers and picks one per {@code parse}, with the switch
 * resolved lazily and cached, so these tests drive {@link JsonUtils#strictTextualCoercionCache} and
 * assert through the real public entry points. Nothing here reflects over {@code static final}
 * state or depends on execution order.
 */
class JsonUtilsStrictCoercionTest {

    /** Minimal DTO: one String field, public so Jackson needs no accessors. */
    static class Dto {
        public String name;
    }

    @BeforeEach
    @AfterEach
    void dropResolvedState() {
        JsonUtils.strictTextualCoercionCache(null);
    }

    // ---------------------------------------------------------------- default: STRICT

    /**
     * The headline behaviour change of 1.2.0, asserted on the real shared decode path with no env
     * var set: integer / float / boolean into a String field is now a decode failure, which the
     * gRPC server maps to {@code INVALID_ARGUMENT} instead of letting the value slip past field
     * validation into the method body.
     */
    @ParameterizedTest(name = "default (no env): {0} into a String field -> JsonDecodeException")
    @ValueSource(strings = {"{\"name\":12345}", "{\"name\":1.5}", "{\"name\":true}", "{\"name\":false}"})
    void byDefault_scalarIntoStringField_fails(String json) {
        assertThrows(JsonDecodeException.class, () -> JsonUtils.parse(json, Dto.class));
    }

    /** The {@code parse(String, Type)} overload defaults to strict too. */
    @Test
    void byDefault_typeOverloadIsStrictToo() {
        Type dtoType = Dto.class;
        assertThrows(JsonDecodeException.class, () -> JsonUtils.parse("{\"name\":12345}", dtoType));
        assertEquals("ok", JsonUtils.<Dto>parse("{\"name\":\"ok\"}", dtoType).name);
    }

    /** Strict must not disturb the happy path: real strings, nulls and absent fields still decode. */
    @Test
    void byDefault_stringInputStillDecodes() {
        assertEquals("13800138000",
                JsonUtils.<Dto>parse("{\"name\":\"13800138000\"}", Dto.class).name);
        assertNull(JsonUtils.<Dto>parse("{\"name\":null}", Dto.class).name);
        assertNull(JsonUtils.<Dto>parse("{}", Dto.class).name);
    }

    /** Array/object into a String field failed before 1.2.0 and still fails. */
    @Test
    void byDefault_arrayOrObjectIntoStringField_stillFails() {
        assertThrows(JsonDecodeException.class, () -> JsonUtils.parse("{\"name\":[]}", Dto.class));
        assertThrows(JsonDecodeException.class, () -> JsonUtils.parse("{\"name\":{}}", Dto.class));
    }

    /**
     * Strict changes coercion and nothing else. Guards the drift risk the two-mapper design
     * introduces: {@code FAIL_ON_UNKNOWN_PROPERTIES=false} has to hold on the strict mapper too.
     */
    @Test
    void byDefault_unknownPropertiesStillTolerated() {
        assertEquals("ok", JsonUtils.<Dto>parse("{\"name\":\"ok\",\"unknown\":1}", Dto.class).name);
    }

    /**
     * An untyped target has no textual slot, so strict decoding does not touch it — this is why
     * {@code JwsCredential} (HashMap) and {@code McpHandler} (Object) are unaffected.
     */
    @Test
    void byDefault_untypedTargetsAreUnaffected() {
        var map = JsonUtils.parse("{\"a\":1,\"b\":true}", java.util.HashMap.class);
        assertEquals(Integer.valueOf(1), map.get("a"));
        assertEquals(Boolean.TRUE, map.get("b"));
    }

    // ---------------------------------------------------------------- kill switch pulled: LENIENT

    /**
     * With the kill switch resolved to lenient, the pre-1.2.0 behaviour is back verbatim.
     *
     * <p>{@code System.getenv} has no setter, so the resolved state is primed directly; the env
     * string -> boolean step is covered by the resolver tests below and the whole chain end-to-end
     * by the native-image check.
     */
    @Test
    void killSwitchPulled_restoresLenientStringification() {
        JsonUtils.strictTextualCoercionCache(false);
        assertEquals("12345", JsonUtils.<Dto>parse("{\"name\":12345}", Dto.class).name);
        assertEquals("1.5", JsonUtils.<Dto>parse("{\"name\":1.5}", Dto.class).name);
        assertEquals("true", JsonUtils.<Dto>parse("{\"name\":true}", Dto.class).name);
        assertEquals("12345", JsonUtils.<Dto>parse("{\"name\":12345}", (Type) Dto.class).name);
    }

    /** Even pulled, the kill switch does not resurrect array/object coercion — there never was any. */
    @Test
    void killSwitchPulled_arrayOrObjectStillFails() {
        JsonUtils.strictTextualCoercionCache(false);
        assertThrows(JsonDecodeException.class, () -> JsonUtils.parse("{\"name\":[]}", Dto.class));
    }

    /** Serialization has no coercion semantics and always uses the lenient mapper. */
    @Test
    void stringify_isUnaffectedByTheSwitch() {
        var dto = new Dto();
        dto.name = "x";
        var strict = JsonUtils.stringify(dto);
        JsonUtils.strictTextualCoercionCache(false);
        assertEquals(strict, JsonUtils.stringify(dto));
    }

    // ---------------------------------------------------------------- switch value semantics

    /** Unset keeps the strict default. */
    @Test
    void unset_isStrict() {
        assertTrue(JsonUtils.strictTextualCoercion(null));
    }

    /**
     * Blank is treated as unset, i.e. STRICT. Deliberate: an empty value is far more likely an
     * unsubstituted template variable than a considered decision to disable the guard, and an
     * accident must not silently widen what the service accepts.
     */
    @ParameterizedTest(name = "blank ''{0}'' -> strict")
    @EmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    void blank_isStrict(String raw) {
        assertTrue(JsonUtils.strictTextualCoercion(raw));
    }

    /** An explicit affirmative keeps strict, so the intuitive spelling cannot silently disable it. */
    @ParameterizedTest(name = "''{0}'' -> strict")
    @ValueSource(strings = {"true", "TRUE", "True", " true ", "1", " 1 "})
    void explicitAffirmative_isStrict(String raw) {
        assertTrue(JsonUtils.strictTextualCoercion(raw));
    }

    /** The documented way to pull the kill switch. */
    @ParameterizedTest(name = "''{0}'' -> lenient")
    @ValueSource(strings = {"false", "FALSE", "False", " false ", "0", " 0 "})
    void explicitFalseOrZero_isLenient(String raw) {
        assertFalse(JsonUtils.strictTextualCoercion(raw));
    }

    /**
     * Unrecognised values fall to LENIENT — the deliberate asymmetry for a kill switch. Whoever
     * sets this is mid-incident; an escape hatch that only opens when spelled perfectly fails
     * exactly when it is needed. A typo lands somewhere recoverable instead.
     */
    @ParameterizedTest(name = "unrecognised ''{0}'' -> lenient")
    @ValueSource(strings = {"fasle", "yes", "no", "off", "on", "2", "-1", "disabled", "null"})
    void unrecognisedValue_isLenient(String raw) {
        assertDoesNotThrow(() -> JsonUtils.strictTextualCoercion(raw));
        assertFalse(JsonUtils.strictTextualCoercion(raw));
    }

    /**
     * Only Java's own whitespace notion counts. {@code String.isBlank()} and {@code trim()} do NOT
     * recognise NBSP (U+00A0), figure space (U+2007) or narrow NBSP (U+202F), so a value copied out
     * of a document with one of those attached is neither blank nor a recognised token — it falls
     * to LENIENT, exactly like any other unrecognised value.
     *
     * <p>This is the safe-side outcome under the kill-switch rule, so the behaviour stands. It is
     * pinned here because it is genuinely surprising: {@code "true\u00A0"} means LENIENT, i.e.
     * someone pasting an affirmative can silently end up with strict decoding OFF. SPEC §4 says so
     * out loud.
     */
    @ParameterizedTest(name = "non-Java whitespace ''{0}'' -> lenient (not blank, not recognised)")
    @ValueSource(strings = {"true\u00A0", "\u00A0true", "1\u2007", "false\u202F", "\u00A0"})
    void nonJavaWhitespace_isNotTrimmed_soFallsToLenient(String raw) {
        assertFalse(raw.isBlank(), "precondition: Java does not consider this blank");
        assertFalse(JsonUtils.strictTextualCoercion(raw));
    }

    /**
     * An unreadable environment resolves to lenient, and never throws. Positive control first:
     * {@code System.getenv(null)} genuinely throws {@code NullPointerException}, so the wrapper
     * assertion below cannot pass vacuously.
     */
    @Test
    void unreadableEnvironment_isLenient_andNeverThrows() {
        assertThrows(NullPointerException.class, () -> System.getenv(null));
        assertDoesNotThrow(() -> JsonUtils.strictFromEnv(null));
        assertFalse(JsonUtils.strictFromEnv(null));
    }

    /** An absent variable resolves through the real env read to strict. */
    @Test
    void absentEnvironmentVariable_isStrict() {
        assertTrue(JsonUtils.strictFromEnv("KRPC_JSON_STRICT_ABSENT_IN_TESTS"));
    }

    /** The kill switch is env-only; the removed system property must not resurrect a second leg. */
    @Test
    void systemProperty_isNotConsulted() {
        System.setProperty("rpc.json.strictTextualCoercion", "false");
        try {
            JsonUtils.strictTextualCoercionCache(null);
            assertThrows(JsonDecodeException.class,
                    () -> JsonUtils.parse("{\"name\":12345}", Dto.class));
        } finally {
            System.clearProperty("rpc.json.strictTextualCoercion");
        }
    }

    // ---------------------------------------------------------------- when the switch is read

    /**
     * The switch is resolved on the decode path and the resolution is cached.
     *
     * <p><b>What this locks:</b> the resolution is cached (priming it changes the next
     * {@code parse}, and repeated parses do not re-resolve), and the mapper choice is made from
     * that resolved state rather than frozen at class-initialization time.
     *
     * <p><b>What this does NOT lock:</b> that the FIRST resolution avoids {@code <clinit>} — the
     * test primes the cache itself, so a regression that also resolved in a static initializer
     * would still pass; and native behaviour, which only a real image can show (it was checked
     * separately, outside this suite).
     */
    @Test
    void switchIsResolvedOnTheDecodePath_andCached() {
        assertThrows(JsonDecodeException.class, () -> JsonUtils.parse("{\"name\":12345}", Dto.class));

        JsonUtils.strictTextualCoercionCache(false);
        assertEquals("12345", JsonUtils.<Dto>parse("{\"name\":12345}", Dto.class).name);
        assertEquals("12345", JsonUtils.<Dto>parse("{\"name\":12345}", Dto.class).name);

        JsonUtils.strictTextualCoercionCache(null);
        assertThrows(JsonDecodeException.class, () -> JsonUtils.parse("{\"name\":12345}", Dto.class));
    }
}
