/**
 * Alipay.com Inc.
 * Copyright (c) 2004-2021 All Rights Reserved.
 */
package tech.krpc.util;

import java.lang.reflect.Type;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author martin.cong
 * @version JsonUtils: JsonUtils.java, v 0.1 2021年10月17日 00:06 young Exp $
 */
@Slf4j
public abstract class JsonUtils {
    //private static final Map<ParameterizedType, JavaType> TYPES_MAP = new ConcurrentHashMap<>();

    /**
     * The lenient mapper — the pre-1.2 decoding behaviour, reachable only via the
     * {@link #STRICT_TEXTUAL_COERCION_ENV} kill switch. Serialization always uses this one.
     */
    static final ObjectMapper MAPPER;

    /**
     * The strict-decoding sibling of {@link #MAPPER}: identical configuration plus
     * {@link #applyStrictTextualCoercion}. This is the DEFAULT decoder. Built unconditionally so
     * that both mappers are fully configured before either escapes the class initializer and
     * neither is ever mutated afterwards — Jackson does not support reconfiguring a mapper once it
     * has been used, so "pick a mapper" is the only thread-safe way to make this switchable at
     * runtime.
     */
    static final ObjectMapper STRICT_MAPPER;

    /**
     * JSON-STRICT kill switch: environment variable {@code KRPC_JSON_STRICT} (the {@code KRPC_*}
     * env family). Strict decoding is the default; set this to {@code false} to fall back to the
     * pre-1.2 lenient behaviour. Env-only by design — a kill switch has to be settable from a
     * deployment manifest without touching the JVM command line.
     */
    public static final String STRICT_TEXTUAL_COERCION_ENV = "KRPC_JSON_STRICT";

    /**
     * Lazily resolved switch state, deliberately NOT read in the class initializer.
     *
     * <p>Under GraalVM native-image Quarkus initializes classes at build time, which bakes static
     * state into the image heap; a switch read in a static block would be frozen at build time and
     * a runtime env var could not flip it (the limitation {@code KrpcOtel.ENABLED} has). Leaving
     * this {@code null} until the first {@code parse} means the image bakes the null and the real
     * read happens at runtime — the same technique as {@code McpHandler.enabled()}. Verified on a
     * real native image.
     *
     * <p>{@code volatile} so the racy single-check terminates: without it a thread could re-resolve
     * on every call forever. The race itself is benign (the resolution is deterministic, so
     * concurrent first-callers just compute the same value), which is why there is no lock.
     */
    private static volatile Boolean strictEnabled;

    static {
        MAPPER = newMapper(true);
        STRICT_MAPPER = newMapper(false);
        applyStrictTextualCoercion(STRICT_MAPPER);
    }

    private static ObjectMapper newMapper(boolean logModuleRegistration) {
        var mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        var jsr310Exists = "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule";
        try {
            var module = Class.forName(jsr310Exists).getDeclaredConstructor().newInstance();
            mapper.registerModule((Module) module);
            if (logModuleRegistration) {
                log.info("jackson jsr310.JavaTimeModule registered");
            }
        } catch (Exception e) {
            //ignore
        }
        //mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        return mapper;
    }

    /**
     * The mapper every {@code parse} decodes with. Resolves the kill switch on first use and caches
     * it; see {@link #strictEnabled} for why the read cannot live in the class initializer.
     */
    private static ObjectMapper decodeMapper() {
        var enabled = strictEnabled;
        if (enabled == null) {
            enabled = strictFromEnv(STRICT_TEXTUAL_COERCION_ENV);
            // Cached even when the read was refused, so a restricted JVM resolves once, not once
            // per parse.
            strictEnabled = enabled;
            if (!enabled) {
                log.warn("krpc json strict textual coercion DISABLED via {}: a JSON number/boolean "
                        + "into a String field is silently stringified again (pre-1.2.0 behaviour)",
                        STRICT_TEXTUAL_COERCION_ENV);
            }
        }
        return enabled ? STRICT_MAPPER : MAPPER;
    }

    /**
     * Read the kill switch from the environment and resolve it. Package-private for tests; never
     * throws.
     *
     * <p>A restricted JVM (SecurityManager, or an invalid key) must not make the decode path fail
     * for configuration reasons. If the environment cannot be read at all then the kill switch is
     * unreachable, and an unreachable kill switch resolves to LENIENT for the same reason an
     * unrecognised value does -- see {@link #strictTextualCoercion(String)}.
     *
     * <p>{@link RuntimeException} rather than {@code Throwable}: that covers every failure
     * {@code System.getenv} documents ({@code SecurityException}, {@code NullPointerException}),
     * while swallowing an {@code Error} would hide a genuine VM failure behind a config default.
     */
    static boolean strictFromEnv(String key) {
        try {
            return strictTextualCoercion(System.getenv(key));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Pure resolver (package-private for tests) for the strict-decoding kill switch. Returns
     * {@code true} for strict decoding, which is the DEFAULT since 1.2.0.
     *
     * <ul>
     *   <li>unset ({@code null}) -- strict;</li>
     *   <li><b>blank</b> (empty or whitespace) -- strict. Deliberate boundary, following the repo's
     *       {@code EnvUtils}/{@code KrpcOtel} blank-means-unset precedent: an empty value is far
     *       more often an unsubstituted template variable ({@code KRPC_JSON_STRICT="${FLAG}"}
     *       collapsing to {@code ""}) than a considered decision to disable the guard, and an
     *       accident must not silently widen what a service accepts;</li>
     *   <li>{@code true} / {@code 1} (case-insensitive, trimmed) -- strict, explicitly;</li>
     *   <li>anything else -- {@code false}, {@code 0}, and every unrecognised value such as
     *       {@code "fasle"} or {@code "yes"} -- <b>lenient</b>.</li>
     * </ul>
     *
     * <p>That last line is the deliberate asymmetry. Under an opt-in flag an unrecognised value
     * should mean "stay in the safe default"; for a kill switch the safe failure mode is the
     * opposite. Whoever sets this is mid-incident, and an escape hatch that only opens when spelled
     * perfectly is an escape hatch that fails exactly when it is needed. A typo therefore lands on
     * lenient -- recoverable -- rather than leaving a service rejecting traffic it used to accept.
     * {@code true}/{@code 1} are still honoured as "keep strict", so the intuitive spelling of
     * ENABLING the guard cannot silently disable it.
     *
     * <p>Never throws.
     */
    static boolean strictTextualCoercion(String env) {
        if (StringUtils.isBlank(env)) {
            return true;
        }
        var v = env.trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    /**
     * Make Jackson refuse to stringify a scalar into a {@code String} target (package-private for
     * tests). Jackson's default for {@link LogicalType#Textual} is {@code TryConvert}, so
     * {@code 12345} / {@code 1.5} / {@code true} silently become {@code "12345"} / {@code "1.5"} /
     * {@code "true"} and slip past field validation into the method body. Array and object shapes
     * already fail by default and are left alone.
     */
    static void applyStrictTextualCoercion(ObjectMapper mapper) {
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    /**
     * Prime the resolved kill-switch state. Package-private, for tests only.
     *
     * <p>{@code null} drops the cache, modelling the native-image starting state (class
     * initialized, cache baked as {@code null}) so the "resolved on the decode path, not at
     * class-init" property can be asserted. An explicit value stands in for an environment the
     * test JVM cannot set — {@code System.getenv} has no setter, so this is the only way to
     * exercise the lenient branch of {@link #decodeMapper()} in-process. The env read itself is
     * covered by {@link #strictFromEnv} / {@link #strictTextualCoercion} and end-to-end by the
     * native-image check.
     */
    static void strictTextualCoercionCache(Boolean state) {
        strictEnabled = state;
    }

    public static String stringify(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     *  通用类型转换
     *
     *  比如要转换为List<Map<String, String>>
     *
     *  TypeReference<List<Map<String, String>>> MAP_TYPE_REFERENCE = new TypeReference<>() {};
     *  MAP_TYPE_REFERENCE.getType()
     *
     */
    public static <T> T parse(String json, Type type) {
        var mapper = decodeMapper();
        try {
            return (T)mapper.readValue(json,mapper.constructType(type));
        } catch (JsonProcessingException e) {
            // AUD-omp-31: typed + sanitized. Never leak Jackson internals (field/class names, offsets)
            // to a client; the raw exception is kept as the cause for server-side logs only.
            throw new JsonDecodeException("malformed JSON: cannot decode request body", e);
        }
    }

    /**
     * 泛型使用
     */
    public static <T> T parse(String json, Class<T> type) {
        try {
            return (T) decodeMapper().readValue(json,type);
        } catch (JsonProcessingException e) {
            // AUD-omp-31: see the Type overload above.
            throw new JsonDecodeException("malformed JSON: cannot decode request body", e);
        }
    }

}