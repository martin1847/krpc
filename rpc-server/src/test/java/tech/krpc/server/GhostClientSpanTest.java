package tech.krpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import tech.krpc.annotation.RpcService;
import tech.krpc.client.AsyncClient;
import tech.krpc.client.AsyncMethod;
import tech.krpc.client.RpcClientFactory;
import tech.krpc.context.KrpcOtel;
import tech.krpc.model.RpcResult;

/**
 * OTEL-002 Fix 2 (field defect #2), REGRESSION GUARD — not a field-defect exclusion (R1-6).
 *
 * <p>The field ghost: a downstream SERVER span references a CLIENT span-id as parent, but no CLIENT
 * span body reaches the backend; traceId continuity intact. This test proves only that, under a
 * <b>single shared</b> {@code BatchSpanProcessor} + parent-based sampler + a successful
 * {@code forceFlush}, krpc's client span lifecycle exports the CLIENT body with correct parentage
 * for both the sync ({@code blockingUnaryCall}) and async ({@code asyncUnaryCall}) paths — i.e. it
 * excludes an in-process span-lifecycle bug.
 *
 * <p>It deliberately does NOT reproduce the field's independent A/B boundary: service A and B share
 * one {@code OpenTelemetrySdk}/exporter here and the flush always succeeds, so it cannot exercise A
 * exporting its CLIENT span through a separate OTLP pipeline, an A-side queue drop, or A pod
 * termination before its batch drains. Those remain OPEN and are gated on the consumer's staging
 * repro (see OTEL-002_IMPL_omp.md, Fix 2). The sampler-flag exclusion argument holds only under
 * parent-based head sampling on the downstream.
 */
class GhostClientSpanTest {

    static final String APP = "otel-ghost-it";

    @RpcService("Ledger")
    public interface LedgerService {
        RpcResult<String> record(String note);
    }

    @RpcService("Order")
    public interface OrderService {
        RpcResult<String> place(String note);
        RpcResult<String> placeAsync(String note);
    }

    public static final class LedgerServiceImpl implements LedgerService {
        @Override
        public RpcResult<String> record(String note) {
            return RpcResult.ok("ledgered:" + note);
        }
    }

    public static final class OrderServiceImpl implements OrderService {
        private final LedgerService ledger;
        private final AsyncClient<LedgerService> asyncLedger;
        final CountDownLatch asyncDone = new CountDownLatch(1);

        OrderServiceImpl(LedgerService ledger) {
            this.ledger = ledger;
            this.asyncLedger = new AsyncClient<>(ledger);
        }

        @Override
        public RpcResult<String> place(String note) {
            var res = ledger.record(note);
            return RpcResult.ok("ordered:" + res.getData());
        }

        @Override
        public RpcResult<String> placeAsync(String note) {
            asyncLedger.call("record", note, new AsyncMethod.ResultObserver<String>() {
                @Override
                public void onError(Throwable t) {
                    asyncDone.countDown();
                }

                @Override
                public void onSuccess(RpcResult<String> res) {
                    asyncDone.countDown();
                }

                @Override
                public void onCompleted() {
                }
            });
            return RpcResult.ok("ordered-async");
        }
    }

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetrySdk sdk;
    private Server serverA;
    private Server serverB;
    private ManagedChannel channelA;
    private ManagedChannel channelB;
    private ExecutorService execA;
    private ExecutorService execB;
    private OrderService orderClient;
    private OrderServiceImpl orderImpl;

    @BeforeEach
    void setUp() throws Exception {
        KrpcOtel.install(OpenTelemetry.noop());
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                // Staging pipeline: async batch processor (NOT SimpleSpanProcessor) + parent-based
                // sampler. Short delay so the background flush is exercised without a long wait.
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(50, TimeUnit.MILLISECONDS)
                        .build())
                .setSampler(Sampler.parentBased(Sampler.alwaysOn()))
                .build();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        KrpcOtel.install(sdk);

        int portB = freePort();
        execB = Executors.newVirtualThreadPerTaskExecutor();
        serverB = new RpcServerBuilder.Builder(APP, portB).executor(execB)
                .addService(new LedgerServiceImpl()).build().startServer();
        channelB = ManagedChannelBuilder.forAddress("127.0.0.1", portB).usePlaintext().build();
        LedgerService ledgerClient = new RpcClientFactory(APP, channelB).get(LedgerService.class);

        int portA = freePort();
        execA = Executors.newVirtualThreadPerTaskExecutor();
        orderImpl = new OrderServiceImpl(ledgerClient);
        serverA = new RpcServerBuilder.Builder(APP, portA).executor(execA)
                .addService(orderImpl).build().startServer();
        channelA = ManagedChannelBuilder.forAddress("127.0.0.1", portA).usePlaintext().build();
        orderClient = new RpcClientFactory(APP, channelA).get(OrderService.class);
    }

    @AfterEach
    void tearDown() {
        if (channelA != null) channelA.shutdownNow();
        if (channelB != null) channelB.shutdownNow();
        if (serverA != null) serverA.shutdownNow();
        if (serverB != null) serverB.shutdownNow();
        if (execA != null) execA.shutdownNow();
        if (execB != null) execB.shutdownNow();
        if (tracerProvider != null) tracerProvider.shutdown();
        KrpcOtel.install(OpenTelemetry.noop());
    }

    @Test
    void syncChain_clientSpanBodyIsExported() throws Exception {
        Span root = sdk.getTracer("test").spanBuilder("inbound").setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope s = root.makeCurrent()) {
            assertTrue(orderClient.place("x").isOk());
        } finally {
            root.end();
        }
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);

        SpanData serverB = awaitSpan(SpanKind.SERVER, "/record");
        assertClientParentExported(serverB);
    }

    @Test
    void asyncChain_clientSpanBodyIsExported() throws Exception {
        Span root = sdk.getTracer("test").spanBuilder("inbound").setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope s = root.makeCurrent()) {
            assertTrue(orderClient.placeAsync("y").isOk());
        } finally {
            root.end();
        }
        assertTrue(orderImpl.asyncDone.await(10, TimeUnit.SECONDS), "async ledger call must complete");
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);

        SpanData serverB = awaitSpan(SpanKind.SERVER, "/record");
        assertClientParentExported(serverB);
    }

    /** The core ghost check: SERVER-B's parent must be a CLIENT span whose body was exported. */
    private void assertClientParentExported(SpanData serverB) {
        String parentId = serverB.getParentSpanId();
        SpanData clientParent = null;
        for (SpanData s : exporter.getFinishedSpanItems()) {
            if (s.getSpanId().equals(parentId)) {
                clientParent = s;
                break;
            }
        }
        assertNotNull(clientParent, () -> "GHOST: SERVER-B parent CLIENT span " + parentId
                + " was never exported. Exported: " + exporter.getFinishedSpanItems());
        assertEquals(SpanKind.CLIENT, clientParent.getKind(),
                "SERVER-B's parent must be the outbound CLIENT span");
        assertEquals(serverB.getTraceId(), clientParent.getTraceId(), "same trace");
    }

    private SpanData awaitSpan(SpanKind kind, String nameSuffix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (SpanData s : exporter.getFinishedSpanItems()) {
                if (s.getKind() == kind && s.getName().endsWith(nameSuffix)) {
                    return s;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no " + kind + " span ending '" + nameSuffix + "' in "
                + exporter.getFinishedSpanItems());
    }

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
