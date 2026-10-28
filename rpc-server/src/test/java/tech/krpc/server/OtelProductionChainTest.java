package tech.krpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import tech.krpc.annotation.RpcService;
import tech.krpc.client.RpcClientFactory;
import tech.krpc.context.KrpcOtel;
import tech.krpc.context.TraceMeta;
import tech.krpc.model.RpcResult;

/**
 * OTEL-001 B2 (ADR-0006): the production-chain container test. Two real krpc netty servers on
 * loopback ports, stood up through the <b>production registration path</b>
 * ({@link RpcServerBuilder} → {@code .intercept(OtelServerInterceptor)}), driven by real krpc
 * clients ({@link RpcClientFactory} → {@code MethodCallProxyHandler} → {@code OtelClientInterceptor}
 * + {@code PropagateTraceCall}). Order-service A's handler calls Ledger-service B over the wire.
 *
 * <p>Asserts the full chain under an in-process SDK exporter: inbound → SERVER-A → CLIENT → SERVER-B
 * as one trace with correct parentage, exactly one {@code traceparent} on the A→B wire hop, and an
 * exception path that ends the B-hop spans with ERROR status.
 */
class OtelProductionChainTest {

    static final String APP = "otel-chain-it";

    @RpcService("Ledger")
    public interface LedgerService {
        RpcResult<String> record(String note);
        RpcResult<String> boom();
    }

    @RpcService("Order")
    public interface OrderService {
        RpcResult<String> place(String note);
        RpcResult<String> placeFailing();
    }

    public static final class LedgerServiceImpl implements LedgerService {
        final AtomicInteger inboundTraceparentCount = new AtomicInteger(-1);

        @Override
        public RpcResult<String> record(String note) {
            // Count the traceparent headers actually received on the wire hop (must be exactly one).
            var headers = ServerContext.current().getHeaders();
            var all = headers.getAll(TraceMeta.TRACEPARENT_KEY);
            int n = 0;
            if (all != null) {
                for (String ignored : all) {
                    n++;
                }
            }
            inboundTraceparentCount.set(n);
            return RpcResult.ok("ledgered:" + note);
        }

        @Override
        public RpcResult<String> boom() {
            throw new RuntimeException("ledger down");
        }
    }

    public static final class OrderServiceImpl implements OrderService {
        private final LedgerService ledger;

        OrderServiceImpl(LedgerService ledger) {
            this.ledger = ledger;
        }

        @Override
        public RpcResult<String> place(String note) {
            var res = ledger.record(note);
            return RpcResult.ok("ordered:" + res.getData());
        }

        @Override
        public RpcResult<String> placeFailing() {
            try {
                ledger.boom();
                return RpcResult.ok("unexpected-success");
            } catch (RuntimeException e) {
                // B failed over the wire; A completes cleanly so the assertion isolates the B-hop spans.
                return RpcResult.error(13, "downstream ledger failed");
            }
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
    private LedgerServiceImpl ledgerImpl;
    private OrderService orderClient;

    @BeforeEach
    void setUp() throws Exception {
        KrpcOtel.install(OpenTelemetry.noop());
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        KrpcOtel.install(sdk);

        int portB = freePort();
        execB = Executors.newVirtualThreadPerTaskExecutor();
        ledgerImpl = new LedgerServiceImpl();
        serverB = new RpcServerBuilder.Builder(APP, portB).executor(execB)
                .addService(ledgerImpl).build().startServer();
        channelB = ManagedChannelBuilder.forAddress("127.0.0.1", portB).usePlaintext().build();
        LedgerService ledgerClient = new RpcClientFactory(APP, channelB).get(LedgerService.class);

        int portA = freePort();
        execA = Executors.newVirtualThreadPerTaskExecutor();
        serverA = new RpcServerBuilder.Builder(APP, portA).executor(execA)
                .addService(new OrderServiceImpl(ledgerClient)).build().startServer();
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
    void inboundToOrderToLedgerIsOneTraceWithCorrectParentage() throws Exception {
        Span root = sdk.getTracer("test")
                .spanBuilder("inbound").setSpanKind(SpanKind.SERVER).startSpan();
        RpcResult<String> res;
        try (Scope scope = root.makeCurrent()) {
            res = orderClient.place("x");
        } finally {
            root.end();
        }
        assertTrue(res.isOk(), () -> "order must succeed, got " + res.getCode() + " " + res.getMsg());

        SpanData serverA = awaitSpan(SpanKind.SERVER, "/place");
        SpanData clientRecord = awaitSpan(SpanKind.CLIENT, "/record");
        SpanData serverB = awaitSpan(SpanKind.SERVER, "/record");
        SpanData clientPlace = awaitSpan(SpanKind.CLIENT, "/place");

        String trace = root.getSpanContext().getTraceId();
        assertEquals(trace, clientPlace.getTraceId(), "CLIENT place must join the inbound trace");
        assertEquals(trace, serverA.getTraceId(), "SERVER-A must join the inbound trace");
        assertEquals(trace, clientRecord.getTraceId(), "CLIENT record must stay in the trace");
        assertEquals(trace, serverB.getTraceId(), "SERVER-B must stay in the trace across the hop");

        // Parent chain: inbound -> CLIENT place -> SERVER-A -> CLIENT record -> SERVER-B.
        assertEquals(root.getSpanContext().getSpanId(), clientPlace.getParentSpanId());
        assertEquals(clientPlace.getSpanId(), serverA.getParentSpanId());
        assertEquals(serverA.getSpanId(), clientRecord.getParentSpanId(),
                "the A->B CLIENT span must be a child of SERVER-A (context reached the handler)");
        assertEquals(clientRecord.getSpanId(), serverB.getParentSpanId(),
                "SERVER-B must be a child of the A->B CLIENT span (propagation across the wire hop)");

        // Exactly one traceparent header on the A->B wire hop (OTel + MDC coexistence, no duplicate).
        assertEquals(1, ledgerImpl.inboundTraceparentCount.get(),
                "exactly one traceparent must reach service B on the wire");
    }

    @Test
    void exceptionPathEndsHopSpansWithErrorStatus() throws Exception {
        RpcResult<String> res = orderClient.placeFailing();
        assertEquals(13, res.getCode(), "order must surface the downstream failure");

        SpanData serverBoom = awaitSpan(SpanKind.SERVER, "/boom");
        SpanData clientBoom = awaitSpan(SpanKind.CLIENT, "/boom");
        assertEquals(StatusCode.ERROR, serverBoom.getStatus().getStatusCode(),
                "SERVER-B span for a throwing handler must end with ERROR status");
        assertEquals(StatusCode.ERROR, clientBoom.getStatus().getStatusCode(),
                "the A->B CLIENT span must end with ERROR status when B fails");
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
