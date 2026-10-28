package tech.krpc.examples.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OTEL-003 round 2: the staging field-② signature reproduced — <b>ghost parent with traceId
 * continuity intact</b>. Same double-span assembly as {@link OtelDoubleServerSpanQuarkusTest}, but
 * the legacy filter also carries the classic self-help lifecycle bug: it {@code makeCurrent()}s its
 * SERVER span but never {@code end()}s it (leak). The outbound CLIENT parents to that legacy span,
 * which is never exported → the downstream span's parent is a GHOST, while every other span stays in
 * the same trace.
 *
 * <p>This is the mechanism behind the OpenObserve field finding "downstream SERVER spans reference
 * parent span-ids that have NO span object anywhere; traceId continuity everywhere is FINE." It is
 * driven entirely by the consumer's legacy filter — the fix is to delete it (see the migration
 * ruling in {@code docs/orchestration/OTEL-003_IMPL_omp.md}).
 */
@QuarkusTest
@TestProfile(OtelLegacyFilterGhostQuarkusTest.LegacyFilterLeaking.class)
class OtelLegacyFilterGhostQuarkusTest {

    public static final class LegacyFilterLeaking implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("otel003.legacy-filter.enabled", "true",
                    "otel003.legacy-filter.leak", "true");
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
    void leakingLegacyFilterProducesGhostParent_withTraceIdContinuityIntact() throws Exception {
        String out = caller.callChain("ghost");
        assertEquals("chained:Hello, ghost!", out, "chain call must succeed end to end");

        SpanData serverChain = awaitSpan(SpanKind.SERVER, "/chain");
        SpanData clientHello = awaitSpan(SpanKind.CLIENT, "/hello");

        // traceId continuity intact across the whole chain.
        assertEquals(serverChain.getTraceId(), clientHello.getTraceId(),
                "all spans stay in one trace (continuity intact)");

        // The CLIENT's parent is the leaked legacy filter span, which never exported → GHOST.
        String ghostParent = clientHello.getParentSpanId();
        assertTrue(ghostParent != null && !ghostParent.equals("0000000000000000"),
                "CLIENT/hello has a (ghost) parent id, not a root");
        assertNull(findBySpanId(ghostParent),
                "GHOST: CLIENT/hello parent span " + ghostParent + " was never exported (leaked legacy filter span)");
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
                .map(s -> s.getKind() + " " + s.getName()).toList());
    }
}
