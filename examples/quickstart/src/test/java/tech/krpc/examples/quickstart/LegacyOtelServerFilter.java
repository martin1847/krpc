package tech.krpc.examples.quickstart;

import io.grpc.Metadata;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.krpc.common.FilterChain;
import tech.krpc.filter.GlobalFilter;
import tech.krpc.server.ServerContext;
import tech.krpc.server.ServerFilter;
import tech.krpc.server.ServerResult;

/**
 * OTEL-003 round 3 (test scope only): the consumer's VERBATIM legacy {@code OtelServerFilter}
 * (order-server version, 91 lines — see {@code CONSUMER-FILTER-SOURCE.md}), adjusted only for
 * package/imports and wrapped with test gating. It is <b>well-behaved</b>: it {@code span.end()}s in
 * {@code finally}, injects the CDI {@link OpenTelemetry} (same SDK/exporter as the built-in
 * interceptor per the consumer), extracts inbound context with a lower-casing Metadata getter, and
 * names its tracer scope {@code order-server}.
 *
 * <p>Since 1.1.1 the built-in {@code OtelServerInterceptor} already creates the inbound SERVER span,
 * so this filter is a duplicate SERVER span per hop; because its {@code makeCurrent()} is innermost,
 * the outbound CLIENT parents to THIS span, not the framework interceptor span.
 *
 * <p>Test gating (not in the consumer's source): {@code otel003.legacy-filter.enabled} keeps the
 * other quickstart @QuarkusTests (which model a consumer WITHOUT the filter) unaffected;
 * {@code otel003.legacy-filter.leak} is a HYPOTHETICAL "what if it forgot end()" probe (the verbatim
 * filter does NOT leak — kept only to characterize that branch). {@code @Startup} forces eager
 * injection at boot (lazy instantiation on the krpc dispatch thread intermittently fails CDI
 * injection across @TestProfile restarts).
 */
@ApplicationScoped
@io.quarkus.runtime.Startup
@GlobalFilter
@Unremovable
public class LegacyOtelServerFilter implements ServerFilter {

    @Inject
    OpenTelemetry otel;

    @Inject
    org.eclipse.microprofile.config.Config config;

    private boolean enabled;
    private boolean leak;

    @jakarta.annotation.PostConstruct
    void init() {
        enabled = config.getOptionalValue("otel003.legacy-filter.enabled", Boolean.class).orElse(false);
        leak = config.getOptionalValue("otel003.legacy-filter.leak", Boolean.class).orElse(false);
    }

    /** Verbatim: read gRPC Metadata header (keys are lower-case ASCII). */
    private static final TextMapGetter<Metadata> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Metadata carrier) {
            return carrier.keys();
        }

        @Override
        public String get(Metadata carrier, String key) {
            if (carrier == null) {
                return null;
            }
            return carrier.get(Metadata.Key.of(key.toLowerCase(), Metadata.ASCII_STRING_MARSHALLER));
        }
    };

    @Override
    public ServerResult Invoke(ServerContext ctx, FilterChain<ServerResult, ServerContext> next) throws Throwable {
        if (!enabled) {
            return next.invoke(ctx);
        }
        String service = ctx.getService().getSimpleName();
        String method = ctx.getMethod();

        Context parent = otel.getPropagators().getTextMapPropagator()
                .extract(Context.current(), ctx.getHeaders(), GETTER);

        Tracer tracer = otel.getTracer("order-server");
        Span span = tracer.spanBuilder(service + "/" + method)
                .setSpanKind(SpanKind.SERVER)
                .setParent(parent)
                .startSpan();
        span.setAttribute("rpc.system", "krpc");
        span.setAttribute("rpc.service", service);
        span.setAttribute("rpc.method", method);

        try (Scope scope = span.makeCurrent()) {
            return next.invoke(ctx);
        } catch (Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR, t.getClass().getSimpleName());
            throw t;
        } finally {
            if (!leak) { // verbatim always ends; leak=true is the hypothetical probe only
                span.end();
            }
        }
    }
}
