package tech.krpc.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.netty.handler.codec.http.HttpHeaders;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import tech.krpc.annotation.RpcService;
import tech.krpc.client.RpcClientFactory;
import tech.krpc.context.KrpcOtel;
import tech.krpc.context.TraceMeta;
import tech.krpc.model.RpcResult;
import tech.krpc.server.RpcServerBuilder;
import tech.krpc.server.ServerContext;

/**
 * OTEL-002 Fix 1 (field defect #1): an HTTP webhook/callback face receives an inbound W3C
 * {@code traceparent}, starts a SERVER span, and the handler body makes an outbound krpc gRPC
 * client call. The outbound CLIENT span (and the downstream SERVER span it reaches) MUST join the
 * inbound HTTP trace — i.e. the HTTP SERVER-span context must be bound around the handler body so
 * {@link tech.krpc.client.OtelClientInterceptor} / {@code PropagateTraceCall} inherit it.
 *
 * <p>Staging rc1 showed the HTTP face producing a SERVER span while the downstream krpc CLIENT call
 * opened a <em>new</em> trace (chain broken). This is the red-first reproduction; the gRPC-inbound
 * analogue ({@code OtelProductionChainTest}) already chains correctly.
 */
class HttpToKrpcClientChainTest {

    static final String APP = "otel-http-chain-it";

    @RpcService("Ledger")
    public interface LedgerService {
        RpcResult<String> record(String note);
    }

    public static final class LedgerServiceImpl implements LedgerService {
        final AtomicInteger inboundTraceparentCount = new AtomicInteger(-1);

        @Override
        public RpcResult<String> record(String note) {
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
    }

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Server serverB;
    private ManagedChannel channelB;
    private ExecutorService execB;
    private LedgerServiceImpl ledgerImpl;
    private HttpServer httpServer;
    private CallbackHandler handler;
    private int httpPort;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        KrpcOtel.install(OpenTelemetry.noop());
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        KrpcOtel.install(OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build());

        int portB = freePort();
        execB = Executors.newVirtualThreadPerTaskExecutor();
        ledgerImpl = new LedgerServiceImpl();
        serverB = new RpcServerBuilder.Builder(APP, portB).executor(execB)
                .addService(ledgerImpl).build().startServer();
        channelB = ManagedChannelBuilder.forAddress("127.0.0.1", portB).usePlaintext().build();
        LedgerService ledgerClient = new RpcClientFactory(APP, channelB).get(LedgerService.class);

        httpPort = freePort();
        handler = new CallbackHandler(ledgerClient);
        httpServer = new HttpServer(handler, httpPort);
        httpServer.start();
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (httpServer != null) httpServer.shutdown();
        if (channelB != null) channelB.shutdownNow();
        if (serverB != null) serverB.shutdownNow();
        if (execB != null) execB.shutdownNow();
        if (tracerProvider != null) tracerProvider.shutdown();
        KrpcOtel.install(OpenTelemetry.noop());
    }

    @Test
    void httpInboundContextReachesOutboundKrpcClient() throws Exception {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String parentSpanId = "b7ad6b7169203331";
        String traceparent = "00-" + traceId + "-" + parentSpanId + "-01";

        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/callback"))
                        .header("Content-Type", "application/json")
                        .header("traceparent", traceparent)
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), () -> "callback must succeed: " + r.body());

        SpanData httpServerSpan = awaitSpan(SpanKind.SERVER, "/callback");
        SpanData clientRecord = awaitSpan(SpanKind.CLIENT, "/record");
        SpanData serverRecord = awaitSpan(SpanKind.SERVER, "/record");

        assertEquals(traceId, httpServerSpan.getTraceId(), "HTTP SERVER span must join the inbound trace");
        assertEquals(traceId, clientRecord.getTraceId(),
                "outbound CLIENT call must stay in the HTTP inbound trace (field defect: it started a NEW trace)");
        assertEquals(traceId, serverRecord.getTraceId(),
                "downstream SERVER must stay in the inbound trace across the hop");

        assertEquals(httpServerSpan.getSpanId(), clientRecord.getParentSpanId(),
                "the outbound CLIENT span must be a child of the HTTP SERVER span (context bound around handler)");
        assertEquals(clientRecord.getSpanId(), serverRecord.getParentSpanId(),
                "downstream SERVER must be a child of the outbound CLIENT span");

        assertEquals(1, ledgerImpl.inboundTraceparentCount.get(),
                "exactly one traceparent must reach the downstream on the wire");
    }

    @Test
    void capturedHandlerMdcIdentifiesTheHttpServerSpan() throws Exception {
        // R1-5: with an SDK, logging MDC (traceId/spanId) inside the handler must identify the HTTP
        // SERVER span itself — not the inbound caller's parent span — so handler logs join that span.
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String parentSpanId = "b7ad6b7169203331";
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/callback"))
                        .header("Content-Type", "application/json")
                        .header("traceparent", "00-" + traceId + "-" + parentSpanId + "-01")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), () -> "callback must succeed: " + r.body());

        SpanData httpServerSpan = awaitSpan(SpanKind.SERVER, "/callback");
        assertEquals(httpServerSpan.getTraceId(), handler.mdcTraceId.get(),
                "MDC traceId must equal the exported HTTP SERVER span's traceId");
        assertEquals(httpServerSpan.getSpanId(), handler.mdcSpanId.get(),
                "MDC spanId must equal the SERVER span's own id, not the inbound parent " + parentSpanId);
        assertEquals(parentSpanId, httpServerSpan.getParentSpanId(),
                "sanity: the SERVER span is a child of the inbound parent");
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

    /** HTTP handler whose body performs an outbound krpc client call, like a webhook callback. */
    static final class CallbackHandler extends AbstractHttpHandler {
        private final LedgerService ledger;
        final AtomicReference<String> mdcTraceId = new AtomicReference<>();
        final AtomicReference<String> mdcSpanId = new AtomicReference<>();

        CallbackHandler(LedgerService ledger) {
            this.ledger = ledger;
            postMap.put("/callback", new PostHandler<String>() {
                @Override
                public Class<String> getParamClass() {
                    return String.class;
                }

                @Override
                public String path() {
                    return "/callback";
                }

                @Override
                public boolean useValidator() {
                    return false;
                }

                @Override
                public byte[] handle(String param, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
                    // R1-5: capture the logging MDC visible inside the handler body.
                    mdcTraceId.set(MDC.get(TraceMeta.MDC_TRACE_ID));
                    mdcSpanId.set(MDC.get(TraceMeta.MDC_SPAN_ID));
                    RpcResult<String> res = ledger.record("from-http");
                    return ("{\"ok\":\"" + res.getData() + "\"}").getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public String contextType() {
                    return AbstractHttpHandler.TYPE_JSON;
                }
            });
        }

        @Override
        public Validator getValidator() {
            return null;
        }

        @Override
        public void initHandler() {
        }
    }
}
