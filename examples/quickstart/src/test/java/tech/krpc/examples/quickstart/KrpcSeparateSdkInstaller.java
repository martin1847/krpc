package tech.krpc.examples.quickstart;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.krpc.context.KrpcOtel;

/**
 * OTEL-003 round 3 (test scope only): simulates hypothesis 2a — {@code KrpcOtel.install()} bound to a
 * DIFFERENT {@link OpenTelemetry} than the CDI SDK, specifically one <b>with no span processor /
 * exporter</b>. krpc's interceptors then create real, recording, injectable spans (valid ids on the
 * wire) that are never exported, while the consumer's filter + jdbc (CDI SDK) export normally.
 *
 * <p>Lives in a CDI bean (application classloader) so the SDK instance and the {@code KrpcOtel.install}
 * both run in the same loader as the interceptors — no cross-loader SDK plumbing. The separate SDK
 * shares the JVM-global {@code QuarkusContextStorage}, so cross-instance parentage (krpc CLIENT span
 * as child of the filter's CDI SERVER span) still resolves and traceId continuity holds.
 */
@ApplicationScoped
public class KrpcSeparateSdkInstaller {

    @Inject
    OpenTelemetry cdiOtel;

    private volatile OpenTelemetrySdk separate;

    /** Point krpc at a real-but-unexported SDK (recording spans, no processor). */
    public void installSeparateNoExporterSdk() {
        separate = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build()) // no SpanProcessor => never exports
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        KrpcOtel.install(separate);
    }

    /** Restore krpc to the container's CDI OpenTelemetry (the correct wiring). */
    public void restore() {
        KrpcOtel.install(cdiOtel);
        if (separate != null) {
            separate.getSdkTracerProvider().shutdown();
            separate = null;
        }
    }
}
