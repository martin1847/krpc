package tech.krpc.examples.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OTEL-003 round 3 — hypothesis 2a reproduced: field symptom ② (missing CLIENT body + ghost parent,
 * traceId continuity intact) arises with the WELL-BEHAVED verbatim filter when {@code KrpcOtel} is
 * bound to a DIFFERENT OpenTelemetry than the CDI SDK — one with no exporter (see
 * {@link KrpcSeparateSdkInstaller}).
 *
 * <p>Mechanism: krpc's interceptor/client spans (scope {@code tech.krpc}) are created, recording, and
 * inject valid W3C ids on the wire, but go to the no-exporter SDK → never exported. The consumer's
 * filter SERVER spans (scope {@code order-server}) + jdbc go to the CDI SDK → exported. So the
 * downstream SERVER span parents to the krpc CLIENT span's injected id, which has NO exported body →
 * ghost, while traceId continuity holds (the krpc CLIENT inherits the filter span's trace via the
 * shared QuarkusContextStorage). Each hop injects its own CLIENT id → a DIFFERENT ghost per hop
 * (matches the field's three distinct ghost parent ids).
 *
 * <p><b>This is a wiring hypothesis, gated on the pending discriminator</b> (does the field trace
 * contain {@code tech.krpc}-scope spans?). If the field shows NO {@code tech.krpc} spans → this is the
 * mechanism (root: KrpcOtel.install got a non-CDI/no-exporter instance). If it shows {@code tech.krpc}
 * spans exported → this branch is ruled out (see {@code OtelClientChainQuarkusTest} which proves the
 * standard wiring exports {@code tech.krpc} spans to the same sink). See OTEL-003_IMPL_omp.md matrix.
 */
@QuarkusTest
@TestProfile(OtelKrpcSeparateSdkQuarkusTest.FilterOn.class)
class OtelKrpcSeparateSdkQuarkusTest {

    public static final class FilterOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("otel003.legacy-filter.enabled", "true");
        }
    }

    @Inject
    RecordingSpanExporter spanExporter;

    @Inject
    ChainGrpcCaller caller;

    @Inject
    KrpcSeparateSdkInstaller installer;

    @BeforeEach
    void reset() {
        spanExporter.reset();
    }

    @Test
    void krpcOnSeparateNoExporterSdk_missingClientAndGhostParent_continuityIntact() throws Exception {
        installer.installSeparateNoExporterSdk();
        try {
            String out = caller.callChain("sep");
            assertEquals("chained:Hello, sep!", out, "chain call must still succeed end to end");

            // The consumer's filter SERVER spans (CDI SDK) export; the krpc interceptor/client spans
            // (separate no-exporter SDK) do not.
            SpanData serverChain = awaitSpan(SpanKind.SERVER, "/chain");
            SpanData serverHello = awaitSpan(SpanKind.SERVER, "/hello");
            assertEquals("order-server", serverChain.getInstrumentationScopeInfo().getName(),
                    "only the consumer filter SERVER span is exported (scope order-server)");

            // No tech.krpc spans exported at all (interceptor + client went to the no-exporter SDK).
            List<SpanData> krpcScoped = spanExporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getInstrumentationScopeInfo().getName().equals("tech.krpc"))
                    .collect(Collectors.toList());
            assertTrue(krpcScoped.isEmpty(),
                    "krpc (tech.krpc) spans must be absent from export when KrpcOtel has no exporter; got " + krpcScoped);

            // CLIENT body missing: no exported CLIENT span for /hello.
            boolean anyClientHello = spanExporter.getFinishedSpanItems().stream()
                    .anyMatch(s -> s.getKind() == SpanKind.CLIENT && s.getName().endsWith("/hello"));
            assertTrue(!anyClientHello, "the outbound CLIENT/hello span body must be missing from export");

            // Ghost parent + continuity intact: SERVER/hello's parent (the krpc CLIENT id) is not
            // exported, yet it shares the trace.
            assertEquals(serverChain.getTraceId(), serverHello.getTraceId(),
                    "traceId continuity intact across the hop");
            String ghostParent = serverHello.getParentSpanId();
            assertTrue(ghostParent != null && !ghostParent.equals("0000000000000000"),
                    "SERVER/hello has a (ghost) parent id");
            assertNull(findBySpanId(ghostParent),
                    "GHOST: SERVER/hello parent " + ghostParent + " (krpc CLIENT id) was never exported");
        } finally {
            installer.restore();
        }
    }

    private SpanData findBySpanId(String spanId) {
        for (SpanData s : spanExporter.getFinishedSpanItems()) {
            if (s.getSpanId().equals(spanId)) {
                return s;
            }
        }
        return null;
    }

    private SpanData awaitSpan(SpanKind kind, String nameSuffix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            for (SpanData s : spanExporter.getFinishedSpanItems()) {
                if (s.getKind() == kind && s.getName().endsWith(nameSuffix)) {
                    return s;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no " + kind + " span ending '" + nameSuffix + "' was exported; spans="
                + spanExporter.getFinishedSpanItems().stream()
                .map(s -> s.getKind() + " " + s.getName() + "@" + s.getInstrumentationScopeInfo().getName())
                .toList());
    }
}
