package tech.krpc.examples.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * OTEL-003 round 2: the REAL edge-server assembly — the built-in {@code OtelServerInterceptor} PLUS
 * the consumer's legacy {@link LegacyOtelServerFilter} (double SERVER span), under the real
 * QuarkusContextStorage + BatchSpanProcessor, handler-originated sync-inline outbound krpc call.
 *
 * <p>Reproduces the prime suspect: since 1.1.1 both the framework interceptor and the legacy filter
 * create a SERVER span per call, and — because the filter's {@code makeCurrent()} is innermost — the
 * outbound CLIENT span parents to the LEGACY filter span, not the framework interceptor span. The
 * framework SERVER span is left childless. traceId continuity is intact.
 *
 * <p>The "fix" is consumer-side: delete the legacy filter. With it gone the assembly matches
 * {@link OtelClientChainQuarkusTest} (single SERVER span per hop, CLIENT parents to the framework
 * SERVER span). See {@code docs/orchestration/OTEL-003_IMPL_omp.md} for the migration ruling.
 */
@QuarkusTest
@TestProfile(OtelDoubleServerSpanQuarkusTest.LegacyFilterOn.class)
class OtelDoubleServerSpanQuarkusTest {

    static final String FRAMEWORK_SCOPE = "tech.krpc";
    static final String LEGACY_SCOPE = "order-server";

    public static final class LegacyFilterOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("otel003.legacy-filter.enabled", "true");
        }
    }

    @Inject
    RecordingSpanExporter spanExporter;

    @Inject
    ChainGrpcCaller caller;

    @BeforeEach
    void reset() {
        spanExporter.reset();
    }

    @Test
    void legacyFilterCreatesDoubleServerSpan_andClientParentsToTheLegacySpan() throws Exception {
        String out = caller.callChain("dbl");
        assertEquals("chained:Hello, dbl!", out, "chain call must succeed end to end");

        // Two SERVER spans per method: the framework interceptor span AND the legacy filter span.
        SpanData frameworkChain = awaitSpan(SpanKind.SERVER, "/chain", FRAMEWORK_SCOPE);
        SpanData legacyChain = awaitSpan(SpanKind.SERVER, "chain", LEGACY_SCOPE);
        assertTrue(serverSpansEnding("chain").size() >= 2,
                "the legacy filter + the built-in interceptor must both create a SERVER span for /chain");
        assertEquals(frameworkChain.getTraceId(), legacyChain.getTraceId(), "continuity intact");

        // The outbound CLIENT parents to the LEGACY filter span (innermost makeCurrent), NOT the
        // framework interceptor span — the framework SERVER span is left childless.
        SpanData clientHello = awaitSpan(SpanKind.CLIENT, "/hello", FRAMEWORK_SCOPE);
        assertEquals(legacyChain.getSpanId(), clientHello.getParentSpanId(),
                "CLIENT/hello parents to the LEGACY filter's chain span, not the framework interceptor span");
        assertTrue(!frameworkChain.getSpanId().equals(clientHello.getParentSpanId()),
                "the framework interceptor SERVER span for /chain is left childless (double-span corruption)");
        assertNotNull(findBySpanId(clientHello.getParentSpanId()),
                "with a correct (non-leaking) filter the parent still exports — ghost only appears on leak");
    }

    private List<SpanData> serverSpansEnding(String suffix) {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getKind() == SpanKind.SERVER && s.getName().endsWith(suffix))
                .collect(Collectors.toList());
    }

    private SpanData findBySpanId(String spanId) {
        for (SpanData s : spanExporter.getFinishedSpanItems()) {
            if (s.getSpanId().equals(spanId)) {
                return s;
            }
        }
        return null;
    }

    private SpanData awaitSpan(SpanKind kind, String nameSuffix, String scope) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            for (SpanData s : spanExporter.getFinishedSpanItems()) {
                if (s.getKind() == kind && s.getName().endsWith(nameSuffix)
                        && s.getInstrumentationScopeInfo().getName().equals(scope)) {
                    return s;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no " + kind + " span ending '" + nameSuffix + "' in scope " + scope
                + "; spans=" + spanExporter.getFinishedSpanItems().stream()
                .map(s -> s.getKind() + " " + s.getName() + "@" + s.getInstrumentationScopeInfo().getName())
                .toList());
    }
}
