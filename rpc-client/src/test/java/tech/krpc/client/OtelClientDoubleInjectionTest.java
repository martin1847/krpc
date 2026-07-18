package tech.krpc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ForwardingClientCall;
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
 * OTEL-003 round 6: the "wire-injected parent-id != exported CLIENT span-id" defect class.
 *
 * <p>Pure krpc 1.1.1 satisfies the invariant (see
 * {@code OtelClientMdcParityTest.sdkPresent_clientSpanTraceparent_supersedesStaleMdc_singleHeader}
 * and the quickstart chain test: SERVER.parent == exported CLIENT.spanId). The field divergence
 * (rpc-server 1.1.1 + ext-rpc 1.0.4 + forced rpc-client 1.1.1) therefore requires a SECOND CLIENT
 * injector on the channel, DEEPER than krpc's {@code OtelClientInterceptor} — i.e. OTel gRPC
 * auto-instrumentation (javaagent / {@code GrpcTelemetry}) or any consumer-added
 * {@code ClientInterceptor} that injects a fresh (non-recording / propagation-only) span. That is the
 * CLIENT analog of the double-SERVER-span legacy filter.
 *
 * <p>This test reproduces the field signature deterministically by adding such a deeper injector: the
 * exported CLIENT span id (krpc's) differs from the wire traceparent id, the wire id has no exported
 * span (ghost), and traceId continuity holds. It documents that krpc's injection is NOT authoritative
 * against a deeper interceptor — no krpc-only injection order can win, because the deeper interceptor
 * runs after krpc's {@code super.start(...)}. Root cause + fix direction: OTEL-003_IMPL_omp.md ROUND 6.
 */
class OtelClientDoubleInjectionTest {

    /** Captures the metadata handed to the transport (the actual wire headers). */
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

    /**
     * A channel whose call, in {@code start()}, overwrites the traceparent with a FRESH id (a
     * propagation-only span that is never exported) — modelling OTel gRPC auto-instrumentation running
     * DEEPER than krpc's interceptor (after krpc's {@code super.start(...)}).
     */
    private static final class DeeperInjectorChannel extends ManagedChannel {
        final RecordingClientCall<InputProto, OutputProto> call = new RecordingClientCall<>();
        final String freshSpanId = "abcdef0123456789"; // never a real exported span

        @Override
        @SuppressWarnings("unchecked")
        public <R, P> ClientCall<R, P> newCall(MethodDescriptor<R, P> md, CallOptions opts) {
            ClientCall<InputProto, OutputProto> inner =
                    new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
                        @Override
                        public void start(Listener<OutputProto> l, Metadata headers) {
                            String existing = headers.get(TraceMeta.TRACEPARENT_KEY);
                            if (existing != null) {
                                String traceId = existing.split("-")[1];
                                Metadata.Key<String> k = TraceMeta.TRACEPARENT_KEY;
                                headers.removeAll(k);
                                headers.put(k, "00-" + traceId + "-" + freshSpanId + "-01");
                            }
                            super.start(l, headers);
                        }
                    };
            return (ClientCall<R, P>) inner;
        }

        @Override public String authority() { return "test-authority"; }
        @Override public ManagedChannel shutdown() { return this; }
        @Override public ManagedChannel shutdownNow() { return this; }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    private DeeperInjectorChannel channel;
    private MethodCallProxyHandler<CacheTestRpc>.ChannelMethodInvoker invoker;
    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void setUp() throws Exception {
        KrpcOtel.install(OpenTelemetry.noop());
        MDC.clear();
        channel = new DeeperInjectorChannel();
        var handler = new MethodCallProxyHandler<>(
                "otel-dbl", channel, CacheTestRpc.class, List.of(), null, SerialEnum.JSON);
        var m = CacheTestRpc.class.getMethod("bytesMethod", byte[].class);
        invoker = handler.stubMap.get(m);
        assertNotNull(invoker);
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

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (tracerProvider != null) tracerProvider.shutdown();
        KrpcOtel.install(OpenTelemetry.noop());
    }

    @Test
    void deeperInjectorOverridesKrpcClientSpan_wireIdIsGhost_continuityIntact() {
        Span server = sdk.getTracer("test").spanBuilder("inbound").startSpan();
        try (Scope s = server.makeCurrent()) {
            invoker.makeCall(CallOptions.DEFAULT).start(new ClientCall.Listener<>() {}, new Metadata());
            channel.call.listener.onClose(io.grpc.Status.OK, new Metadata());
        } finally {
            server.end();
        }

        String wire = channel.call.captured.get(TraceMeta.TRACEPARENT_KEY);
        assertNotNull(wire, "a traceparent must be on the wire");
        String wireSpanId = wire.split("-")[2];
        String wireTraceId = wire.split("-")[1];

        SpanData clientSpan = exporter.getFinishedSpanItems().stream()
                .filter(sd -> sd.getKind() == io.opentelemetry.api.trace.SpanKind.CLIENT)
                .findFirst().orElseThrow(() -> new AssertionError("no CLIENT span exported"));

        // Field signature: the wire parent id is NOT the exported CLIENT span id, and it is a ghost
        // (no exported span), while the trace id is continuous.
        assertTrue(!wireSpanId.equals(clientSpan.getSpanId()),
                "reproduces field: wire parent id differs from the exported CLIENT span id");
        assertEquals(clientSpan.getTraceId(), wireTraceId, "traceId continuity intact");
        boolean wireHasBody = exporter.getFinishedSpanItems().stream()
                .anyMatch(sd -> sd.getSpanId().equals(wireSpanId));
        assertTrue(!wireHasBody, "the wire parent id has no exported span (ghost)");
    }
}
