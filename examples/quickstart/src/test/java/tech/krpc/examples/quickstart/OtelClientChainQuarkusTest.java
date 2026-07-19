package tech.krpc.examples.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OTEL-003 (ADR-0006): the staging-like container tests the OTEL-001/002 suites never had. A real
 * Quarkus consumer ({@code quarkus-opentelemetry} CDI SDK + BatchSpanProcessor +
 * <b>QuarkusContextStorage</b>) where a handler makes an OUTBOUND krpc call — the CLIENT-span origin
 * across the wire hop: CLIENT/chain (test caller) -> SERVER/chain -> CLIENT/hello (opened inside the
 * chain handler) -> SERVER/hello.
 *
 * <p><b>Why this suite exists (the OTEL-003 wiring diff).</b> Every earlier OTel container test
 * pulled in {@code opentelemetry-sdk-testing}, whose {@code SettableContextStorageProvider} SPI
 * OTel's {@code LazyStorage} short-circuits to — silently pinning the tests to a ThreadLocal storage
 * that a real Quarkus consumer never uses. This suite drops sdk-testing (see
 * {@link RecordingSpanExporter}) and pins {@code QuarkusContextStorage} (see the quickstart
 * build.gradle test config), so the tests exercise the SAME cross-thread context propagation as
 * staging. {@link #activeContextStorageIsQuarkus()} guards that.
 *
 * <p>Findings (see {@code docs/orchestration/OTEL-003_IMPL_omp.md}): the krpc span lifecycle and
 * header handling are correct on the synchronous path AND across a context-propagating executor,
 * even under QuarkusContextStorage. The trace only breaks when the CONSUMER runs the outbound call
 * on an execution context WITHOUT propagating the caller's context (OTEL-002 R1-8), which no
 * backend-agnostic krpc change can recover.
 */
@QuarkusTest
class OtelClientChainQuarkusTest {

    @Inject
    RecordingSpanExporter spanExporter;

    @Inject
    ChainGrpcCaller caller;

    @BeforeEach
    void reset() {
        spanExporter.reset();
    }

    /**
     * The wiring guard: without it, sdk-testing's ThreadLocal storage would silently make every
     * other assertion here pass for the wrong reason (the exact blind spot that let OTEL-002 miss
     * the staging break). RED if the active storage is not QuarkusContextStorage.
     */
    @Test
    void activeContextStorageIsQuarkus() throws Exception {
        Class<?> lazy = Class.forName("io.opentelemetry.context.LazyStorage");
        var m = lazy.getDeclaredMethod("get");
        m.setAccessible(true);
        String storage = m.invoke(null).getClass().getName();
        assertTrue(storage.startsWith("io.quarkus.opentelemetry.runtime.QuarkusContextStorage"),
                "container tests must run on QuarkusContextStorage; got " + storage);
    }

    /**
     * Pure krpc path (handler + outbound both on the krpc handler virtual thread): the whole chain
     * is one trace with correct parentage and the CLIENT/hello body is exported (no ghost) — under
     * the real QuarkusContextStorage + BatchSpanProcessor.
     */
    @Test
    void syncPath_oneTraceWithClientSpanExported() throws Exception {
        String out = caller.callChain("otel-003");
        assertEquals("chained:Hello, otel-003!", out, "chain call must succeed end to end");
        assertConnectedChain();
    }

    /**
     * Context-propagating executor (MicroProfile {@link org.eclipse.microprofile.context.ManagedExecutor},
     * the Quarkus @Blocking / Mutiny-worker path): propagation captures krpc's SERVER span from
     * QuarkusContextStorage's ThreadLocal fallback and restores it on the worker, so the chain stays
     * connected across the thread hop.
     */
    @Test
    void managedExecutorPath_preservesTraceAcrossThreadHop() throws Exception {
        String out = caller.callChain("managed");
        assertEquals("chained:Hello, managed!", out, "chain call must succeed end to end");
        assertConnectedChain();
    }

    /**
     * Consumer-side propagation break (raw Vert.x duplicated context, no context capture):
     * neither the OTel context nor MDC crosses the hop, so the outbound CLIENT/hello span becomes an
     * orphan ROOT in a DIFFERENT trace than SERVER/chain. This is the staging field symptom
     * ("SERVER orphan root; downstream tree in another trace") and it is a consumer responsibility
     * (OTEL-002 R1-8), NOT a krpc defect — documented here to pin the boundary. If a future krpc
     * change makes this connect, flip the assertions.
     */
    @Test
    void rawVertxHop_breaksTrace_consumerResponsibility() throws Exception {
        String out = caller.callChain("vertx");
        assertEquals("chained:Hello, vertx!", out, "chain call must still succeed end to end");

        SpanData serverChain = awaitSpan(SpanKind.SERVER, "/chain");
        SpanData clientHello = awaitSpan(SpanKind.CLIENT, "/hello");
        assertNotEquals(serverChain.getTraceId(), clientHello.getTraceId(),
                "raw hop drops context: CLIENT/hello lands in a NEW trace (consumer propagation break)");
        assertEquals("0000000000000000", clientHello.getParentSpanId(),
                "CLIENT/hello is an orphan ROOT when the consumer loses context across the hop");
    }

    /** CLIENT/chain -> SERVER/chain -> CLIENT/hello -> SERVER/hello, all one trace, no ghost. */
    private void assertConnectedChain() throws InterruptedException {
        SpanData clientChain = awaitSpan(SpanKind.CLIENT, "/chain");
        SpanData serverChain = awaitSpan(SpanKind.SERVER, "/chain");
        SpanData clientHello = awaitSpan(SpanKind.CLIENT, "/hello");
        SpanData serverHello = awaitSpan(SpanKind.SERVER, "/hello");

        String trace = serverChain.getTraceId();
        assertEquals(trace, clientChain.getTraceId(), "CLIENT/chain must share the trace");
        assertEquals(trace, clientHello.getTraceId(), "CLIENT/hello must share the trace");
        assertEquals(trace, serverHello.getTraceId(), "SERVER/hello must stay in the trace across the hop");

        assertEquals(clientChain.getSpanId(), serverChain.getParentSpanId(),
                "SERVER/chain must be a child of CLIENT/chain");
        assertEquals(serverChain.getSpanId(), clientHello.getParentSpanId(),
                "CLIENT/hello must be a child of SERVER/chain (SERVER context reached the outbound call)");
        assertEquals(clientHello.getSpanId(), serverHello.getParentSpanId(),
                "SERVER/hello must be a child of CLIENT/hello (no ghost parent)");
        assertNotNull(findBySpanId(serverHello.getParentSpanId()),
                "SERVER/hello parent must be an exported span, not a ghost");
    }

    /**
     * OTEL-003 finding #4 guard: each span must be exported exactly once. Exposing the exporter
     * under multiple bean types registers it in the BatchSpanProcessor more than once and every
     * span is exported ×2 (the field's duplicate-span_id signature). One CLIENT/hello span id.
     */
    @Test
    void spansAreExportedExactlyOnce() throws Exception {
        caller.callChain("otel-003");
        awaitSpan(SpanKind.SERVER, "/hello");
        long helloClient = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getKind() == SpanKind.CLIENT && s.getName().endsWith("/hello"))
                .count();
        assertEquals(1L, helloClient,
                "CLIENT/hello must be exported exactly once (no double processor registration)");
    }

    private static void assertNotEquals(String a, String b, String msg) {
        assertTrue(a != null && !a.equals(b), msg);
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
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        throw new AssertionError("no " + kind + " span ending '" + nameSuffix + "' was exported; spans="
                + spans.stream().map(s -> s.getKind() + " " + s.getName()).toList());
    }
}
