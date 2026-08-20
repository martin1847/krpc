package tech.krpc.context;

import io.grpc.Metadata;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import tech.krpc.util.FlagResolution;
import tech.krpc.util.FlagSwitch;

/**
 * ADR-0006: span creation joins the framework via the OpenTelemetry <em>API</em> only. No OTel
 * SDK/exporter enters core (ADR-0001 / NS-3); the SDK arrives from the consumer's stack.
 *
 * <p><b>Explicit injection, never the JVM-global accessor.</b> Core holds a {@code volatile}
 * {@link OpenTelemetry} reference, {@link OpenTelemetry#noop()} until an integration explicitly
 * calls {@link #install(OpenTelemetry)} (the Quarkus/Spring startup hooks wire the container's
 * {@code OpenTelemetry} bean; plain-netty users call it themselves). Core NEVER reads the JVM-global
 * OpenTelemetry accessor: that accessor lazily pins the global to a no-op (or reflectively
 * autoconfigures an SDK) on first read, which would permanently kill tracing for any call that
 * arrives before the consumer registers its SDK and would break the consumer's own later global
 * registration. The per-call fast path here is a single volatile read + a reference compare against
 * {@link OpenTelemetry#noop()} (see {@link #isNoop()}); install order does not matter — calls before
 * install are untraced no-ops, calls after install trace. Full rationale: ADR-0006.
 *
 * <p>Shared instrumentation surface for the gRPC server/client interceptors and the HTTP handler:
 * the kill-switch flag, the installed-{@link OpenTelemetry}-backed tracer/propagator, and a gRPC
 * {@link Metadata} text-map getter/setter.
 *
 * <p><b>Relationship to ADR-0003 (MDC forwarding).</b> ADR-0003's pure-propagation path still
 * forwards the inbound {@code traceparent} verbatim via MDC. When an SDK is installed, the client
 * interceptor creates a real CLIENT span and injects a fresh {@code traceparent} for it;
 * {@link #METADATA_SETTER} <em>overwrites</em> (removeAll + put) so the outbound carries exactly
 * one {@code traceparent} reflecting the new client span — it supersedes the MDC-forwarded parent.
 * With no SDK installed the interceptors short-circuit and inject nothing, so the MDC-forwarded
 * {@code traceparent} survives unchanged: one header either way.
 */
public final class KrpcOtel {

    private KrpcOtel() {}

    /** Instrumentation scope name for krpc-created spans. */
    public static final String SCOPE_NAME = "tech.krpc";

    // RPC semantic-convention attribute keys. Hardcoded (not via the alpha opentelemetry-semconv
    // artifact) so core depends on opentelemetry-api only — smaller surface, native-clean (NS-7).
    public static final AttributeKey<String> RPC_SYSTEM  = AttributeKey.stringKey("rpc.system");
    public static final AttributeKey<String> RPC_SERVICE = AttributeKey.stringKey("rpc.service");
    public static final AttributeKey<String> RPC_METHOD  = AttributeKey.stringKey("rpc.method");
    public static final AttributeKey<Long> RPC_GRPC_STATUS_CODE =
            AttributeKey.longKey("rpc.grpc.status_code");
    public static final String RPC_SYSTEM_GRPC = "grpc";

    /** HTTP response status code (used by the http-server SERVER span). */
    public static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE =
            AttributeKey.longKey("http.response.status_code");

    /** Kill-switch system property; wins over {@link #ENV_ENABLED} when both are configured. */
    static final String PROPERTY_ENABLED = "rpc.otel.enabled";

    /** Kill-switch environment variable (so the switch works without a properties file). */
    static final String ENV_ENABLED = "KRPC_OTEL";

    /** ADR-0003 class A: the default encodes the behaviour we defend — telemetry present. */
    private static final boolean DEFAULT_ENABLED = true;

    /**
     * ADR-0003 requirement 2's safe side for a class-A kill switch: OFF, i.e. the switch stays
     * pressable. A typo'd kill switch ({@code KRPC_OTEL=fasle}) must not silently leave telemetry
     * ON — that is precisely the case the switch exists for, and a behaviour stuck ON in production
     * has no remedy short of a rebuild, while a spuriously OFF one is visible and immediately
     * recoverable by fixing the value.
     */
    private static final boolean SAFE_ENABLED = false;

    // ADR-0003 requirement 3: the memo cell only — resolution happens on the first enabled() call,
    // NEVER here. A static initializer is executed at image BUILD time by GraalVM/Quarkus, which
    // bakes the build machine's environment into the binary and welds the kill switch shut (NS-7).
    private static final FlagSwitch ENABLED =
            new FlagSwitch(PROPERTY_ENABLED + " / " + ENV_ENABLED, DEFAULT_ENABLED);

    /**
     * Kill-switch state: ON unless {@code rpc.otel.enabled} / {@code KRPC_OTEL} explicitly says
     * otherwise, says something unrecognised, or cannot be read (both fall to the safe side, OFF).
     *
     * <p>ADR-0003 requirement 4 — this is the flag's ONE resolution point. Every caller calls this
     * method <em>each time</em> and NEVER copies the result into a field of its own: a captured copy
     * freezes at class-init while this accessor resolves at runtime, so one {@code KRPC_OTEL=false}
     * would be honoured on one path and ignored on another in the same binary. Caching is this
     * accessor's business (two volatile reads and a branch once resolved), never the call site's.
     */
    public static boolean enabled() {
        Boolean memo = ENABLED.resolved();
        return memo != null ? memo : ENABLED.publish(read(PROPERTY_ENABLED, ENV_ENABLED));
    }

    /**
     * Guarded read (package-private so the unit contract can drive the failure branch with a key
     * the JDK rejects): ADR-0003 requirement 2 — a lookup that throws resolves to the safe side
     * instead of propagating out of {@link #enabled()}, so reading the flag can never break a
     * caller (nor, before requirement 3 moved it off the static path, class loading).
     */
    static FlagResolution read(String propertyName, String envName) {
        try {
            return resolve(System.getProperty(propertyName), System.getenv(envName));
        } catch (RuntimeException failure) {
            return FlagResolution.readFailure(SAFE_ENABLED, failure);
        }
    }

    /**
     * Pure resolver (package-private for the unit contract): ADR-0003 requirements 1 and 2 with this
     * flag's own default (ON) and safe side (OFF).
     */
    static FlagResolution resolve(String property, String env) {
        return FlagResolution.of(DEFAULT_ENABLED, SAFE_ENABLED, property, env);
    }

    // The installed OpenTelemetry. Volatile: written once at integration startup, read per call.
    // Default noop() so a consumer that never installs an SDK gets zero behavior + zero cost.
    private static volatile OpenTelemetry otel = OpenTelemetry.noop();

    /**
     * Install the container's {@link OpenTelemetry} (Quarkus/Spring startup hooks pass the CDI/context
     * bean; plain-netty users call this explicitly). Idempotent-safe: a null argument is ignored, and
     * installing again simply replaces the reference (a plain volatile write). NEVER reads or writes
     * the JVM-global OpenTelemetry accessor, so it cannot pin or conflict with the consumer's own global.
     */
    public static void install(OpenTelemetry openTelemetry) {
        if (openTelemetry != null) {
            otel = openTelemetry;
        }
    }

    /**
     * True until an SDK is {@link #install(OpenTelemetry) installed} — i.e. span creation is a no-op.
     * The hot-path check: one volatile read + a reference compare against {@link OpenTelemetry#noop()}.
     * Allocation-free, correct under any install-vs-request ordering, and never reads the JVM global.
     */
    public static boolean isNoop() {
        return otel == OpenTelemetry.noop();
    }

    /** Tracer from the installed OpenTelemetry (a no-op tracer until an SDK is installed). */
    public static Tracer tracer() {
        return otel.getTracer(SCOPE_NAME);
    }

    /** W3C propagator from the installed OpenTelemetry (a no-op propagator until an SDK is installed). */
    public static TextMapPropagator propagator() {
        return otel.getPropagators().getTextMapPropagator();
    }

    /** Reads W3C headers from inbound gRPC {@link Metadata} for context extraction. */
    public static final TextMapGetter<Metadata> METADATA_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Metadata carrier) {
            return carrier == null ? java.util.List.of() : carrier.keys();
        }

        @Override
        public String get(Metadata carrier, String key) {
            if (carrier == null || key == null) {
                return null;
            }
            return carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
        }
    };

    /**
     * Writes W3C headers to outbound gRPC {@link Metadata}. Overwrites (removeAll + put) so the
     * carrier holds a single value per key — the OTel-injected {@code traceparent} supersedes any
     * ADR-0003 MDC-forwarded one (see class doc).
     */
    public static final TextMapSetter<Metadata> METADATA_SETTER = (carrier, key, value) -> {
        if (carrier == null || key == null || value == null) {
            return;
        }
        var k = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
        carrier.removeAll(k);
        carrier.put(k, value);
    };
}
