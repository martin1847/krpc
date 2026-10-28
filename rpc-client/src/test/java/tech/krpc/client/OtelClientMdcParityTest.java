package tech.krpc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import tech.krpc.context.KrpcOtel;
import tech.krpc.context.TraceMeta;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;
import tech.krpc.internal.SerialEnum;

/**
 * OTEL-001 (ADR-0006): the client-side coexistence contract between the OTel CLIENT interceptor
 * (span + W3C injection) and ADR-0003's MDC {@code traceparent} forwarding. Both live in the
 * outbound call chain built by {@link MethodCallProxyHandler#makeCall} (PropagateTraceCall wraps
 * the OTel-intercepted call). The invariant defended here is <b>exactly one {@code traceparent} on
 * the wire in every mode</b>:
 *
 * <ul>
 *   <li>no OTel SDK + inbound MDC traceparent → the MDC value is forwarded, one header
 *       (byte-identical to pre-OTEL ADR-0003 — the no-SDK parity proof);
 *   <li>no OTel SDK + no MDC → no traceparent at all (baseline unchanged);
 *   <li>OTel SDK present → the CLIENT-span traceparent supersedes any stale MDC value, still one
 *       header, and it is a child of the current (server) span.
 * </ul>
 */
class OtelClientMdcParityTest {

    /** Records the metadata + listener handed to {@code start()} for outbound inspection. */
    private static final class RecordingClientCall<Req, Resp> extends ClientCall<Req, Resp> {
        volatile Metadata captured;
        volatile Listener<Resp> listener;

        @Override
        public void start(Listener<Resp> responseListener, Metadata headers) {
            this.listener = responseListener;
            this.captured = headers;
        }

        @Override public void request(int numMessages) {}
        @Override public void cancel(String message, Throwable cause) {}
        @Override public void halfClose() {}
        @Override public void sendMessage(Req message) {}
    }

    private static final class RecordingChannel extends ManagedChannel {
        final RecordingClientCall<InputProto, OutputProto> call = new RecordingClientCall<>();

        @Override
        @SuppressWarnings("unchecked")
        public <R, P> ClientCall<R, P> newCall(MethodDescriptor<R, P> md, CallOptions opts) {
            return (ClientCall<R, P>) call;
        }

        @Override public String authority() { return "test-authority"; }
        @Override public ManagedChannel shutdown() { return this; }
        @Override public ManagedChannel shutdownNow() { return this; }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    private RecordingChannel channel;
    private MethodCallProxyHandler<CacheTestRpc>.ChannelMethodInvoker invoker;
    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void setUp() throws Exception {
        KrpcOtel.install(OpenTelemetry.noop());
        MDC.clear();
        channel = new RecordingChannel();
        var handler = new MethodCallProxyHandler<>(
                "otel-parity", channel, CacheTestRpc.class, List.of(), null, SerialEnum.JSON);
        Method bytesMethod = CacheTestRpc.class.getMethod("bytesMethod", byte[].class);
        invoker = handler.stubMap.get(bytesMethod);
        assertNotNull(invoker, "bytesMethod must have a wired invoker");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (tracerProvider != null) {
            tracerProvider.shutdown();
            tracerProvider = null;
        }
        KrpcOtel.install(OpenTelemetry.noop());
    }

    private void registerSdk() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        KrpcOtel.install(sdk);
    }

    private List<String> outboundTraceparents() {
        Iterable<String> all = channel.call.captured.getAll(TraceMeta.TRACEPARENT_KEY);
        assertNotNull(channel.call.captured, "the call must have been started");
        var list = new java.util.ArrayList<String>();
        if (all != null) {
            all.forEach(list::add);
        }
        return list;
    }

    @Test
    void noSdk_mdcTraceparent_forwardedAsSingleHeader() {
        // ADR-0003 parity: no OTel SDK, an inbound traceparent in MDC → forwarded unchanged, once.
        String parent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        MDC.put(TraceMeta.MDC_TRACEPARENT, parent);

        invoker.makeCall(CallOptions.DEFAULT).start(new NoopListener(), new Metadata());

        assertEquals(List.of(parent), outboundTraceparents(),
                "no-SDK mode must forward exactly the MDC traceparent (ADR-0003 byte parity)");
    }

    @Test
    void noSdk_noMdc_noTraceparent() {
        // Baseline: nothing to forward, no SDK → no traceparent header emitted.
        invoker.makeCall(CallOptions.DEFAULT).start(new NoopListener(), new Metadata());
        assertTrue(outboundTraceparents().isEmpty(),
                "with no SDK and no MDC context, no traceparent may be emitted");
    }

    @Test
    void sdkPresent_clientSpanTraceparent_supersedesStaleMdc_singleHeader() {
        registerSdk();
        // A stale MDC parent from a *different* trace — it must NOT leak onto the wire.
        String staleTraceId = "0af7651916cd43dd8448eb211c80319c";
        MDC.put(TraceMeta.MDC_TRACEPARENT, "00-" + staleTraceId + "-b7ad6b7169203331-01");

        Span server = sdk.getTracer("test")
                .spanBuilder("inbound").startSpan();
        try (Scope scope = server.makeCurrent()) {
            invoker.makeCall(CallOptions.DEFAULT).start(new NoopListener(), new Metadata());
            // End the CLIENT span (RecordingClientCall never fires the listener itself) so it exports.
            channel.call.listener.onClose(io.grpc.Status.OK, new Metadata());
        } finally {
            server.end();
        }

        List<String> traceparents = outboundTraceparents();
        assertEquals(1, traceparents.size(),
                "SDK mode must emit exactly one traceparent (no MDC + OTel duplication)");

        String[] parts = traceparents.get(0).split("-");
        String outTraceId = parts[1];
        String outSpanId = parts[2];

        // The emitted traceparent belongs to the server's trace, not the stale MDC trace.
        assertEquals(server.getSpanContext().getTraceId(), outTraceId,
                "outbound traceparent must carry the current (server) trace id");
        assertNotEquals(staleTraceId, outTraceId,
                "the stale MDC traceparent must be superseded, not forwarded");

        // The emitted span id is the CLIENT span, a child of the server span.
        SpanData client = clientSpan();
        assertEquals(outSpanId, client.getSpanId(),
                "outbound traceparent span id must be the CLIENT span");
        assertEquals(server.getSpanContext().getSpanId(), client.getParentSpanId(),
                "CLIENT span must be a child of the current server span");
    }

    private SpanData clientSpan() {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getKind() == io.opentelemetry.api.trace.SpanKind.CLIENT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CLIENT span was exported"));
    }

    private static final class NoopListener extends ClientCall.Listener<OutputProto> {
    }
}
