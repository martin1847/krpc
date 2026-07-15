package tech.krpc.examples.quickstart;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * OTEL-001 B1d (test scope only): Quarkus OpenTelemetry auto-wires a CDI {@link InMemorySpanExporter}
 * bean as the span exporter, so a test can inject it and assert the spans krpc creates. This bean
 * exists ONLY on the test classpath — the published quickstart ships no OTel SDK/exporter.
 */
@ApplicationScoped
public class InMemorySpanExporterProducer {

    @Produces
    @Singleton
    InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }
}
