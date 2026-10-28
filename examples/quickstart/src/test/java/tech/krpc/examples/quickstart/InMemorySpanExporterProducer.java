package tech.krpc.examples.quickstart;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * OTEL-001 B1d / OTEL-003 (test scope only): Quarkus OpenTelemetry wires a CDI
 * {@code io.opentelemetry.sdk.trace.export.SpanExporter} bean into its BatchSpanProcessor, so a test
 * can inject it and assert the spans krpc creates. This bean exists ONLY on the test classpath — the
 * published quickstart ships no OTel SDK/exporter.
 *
 * <p>OTEL-003: exposes a single {@link RecordingSpanExporter} (built on {@code opentelemetry-sdk}
 * only) instead of sdk-testing's {@code InMemorySpanExporter}, so the tests run on the real
 * {@code QuarkusContextStorage} rather than sdk-testing's ThreadLocal storage (see
 * {@link RecordingSpanExporter}).
 *
 * <p><b>Single producer, single bean type.</b> The producer's bean types include {@code SpanExporter}
 * (a supertype of {@link RecordingSpanExporter}), so Quarkus wires it into the processor exactly
 * once. Exposing the same instance under TWO producer methods ({@code RecordingSpanExporter} AND
 * {@code SpanExporter}) makes Quarkus register it in the BatchSpanProcessor twice, so every span is
 * exported ×2 — the OTEL-003 field finding #4 signature (duplicate span_id ×2), which is a
 * consumer-side SDK-wiring bug, not a krpc defect.
 */
@ApplicationScoped
public class InMemorySpanExporterProducer {

    @Produces
    @Singleton
    RecordingSpanExporter recordingSpanExporter() {
        return new RecordingSpanExporter();
    }
}
