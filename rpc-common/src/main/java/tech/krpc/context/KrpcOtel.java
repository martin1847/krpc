package tech.krpc.context;

import io.grpc.Metadata;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

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

    // Kill-switch: rpc.otel.enabled (system property) or KRPC_OTEL (env), default ON. Default-ON is
    // safe because instrumentation is a no-op without an OTel SDK (see class doc). Resolved once —
    // the interceptors read this at registration, so the disabled path costs nothing per call.
    private static final boolean ENABLED = resolveEnabled(
            System.getProperty("rpc.otel.enabled"), System.getenv("KRPC_OTEL"));

    /**
     * Pure resolver (package-private for tests): explicit {@code false}/{@code 0} on either the
     * system property or the env var disables; anything else (including unset) leaves it ON.
     * The system property wins over the env var when both are set.
     */
    static boolean resolveEnabled(String prop, String env) {
        if (prop != null && !prop.isBlank()) {
            return !isFalse(prop);
        }
        if (env != null && !env.isBlank()) {
            return !isFalse(env);
        }
        return true;
    }

    private static boolean isFalse(String v) {
        v = v.trim();
        return "false".equalsIgnoreCase(v) || "0".equals(v);
    }

    /** Kill-switch state. When false the interceptors are never registered (byte-level absent). */
    public static boolean enabled() {
        return ENABLED;
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
