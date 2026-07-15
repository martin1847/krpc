package tech.krpc.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import tech.krpc.client.OtelClientInterceptor;
import tech.krpc.common.MethodStub;
import tech.krpc.context.KrpcOtel;
import tech.krpc.context.TraceMeta;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;

/**
 * OTEL-001 B1c (ADR-0006): the install-vs-request ordering regression. Proves the r2 landmine is
 * gone — core never reads the JVM-global {@code OpenTelemetry} accessor (which lazily pins the
 * global to no-op on first read), so:
 *
 * <ul>
 *   <li>a call intercepted while krpc is still no-op is untraced (no wrapper, no header) — the
 *       "early call before the SDK arrives" case;
 *   <li>after {@link KrpcOtel#install(OpenTelemetry)}, subsequent calls trace (wrapped + one
 *       traceparent + a CLIENT span);
 *   <li>the JVM global was never touched — a later {@code GlobalOpenTelemetry.set(...)} still
 *       succeeds (would throw {@code IllegalStateException} if core had pinned it).
 * </ul>
 */
class OtelInstallOrderingTest {

    private static final MethodDescriptor<InputProto, OutputProto> MD =
            MethodStub.buildMd("Ordering/echo");

    private static final class RecordingClientCall<Req, Resp> extends ClientCall<Req, Resp> {
        volatile Metadata captured;
        volatile Listener<Resp> listener;

        @Override public void start(Listener<Resp> l, Metadata headers) { this.listener = l; this.captured = headers; }
        @Override public void request(int n) {}
        @Override public void cancel(String m, Throwable c) {}
        @Override public void halfClose() {}
        @Override public void sendMessage(Req m) {}
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
        @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
    }

    private OpenTelemetrySdk sdk;

    @BeforeEach
    void setUp() {
        KrpcOtel.install(OpenTelemetry.noop());
        GlobalOpenTelemetry.resetForTest(); // clean baseline; core never touches this anyway
    }

    @AfterEach
    void tearDown() {
        KrpcOtel.install(OpenTelemetry.noop());
        if (sdk != null) {
            sdk.getSdkTracerProvider().shutdown();
            sdk = null;
        }
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void callBeforeInstallUntraced_callAfterInstallTraced_globalNeverPinned() {
        var interceptor = new OtelClientInterceptor();

        // 1) Early call while still no-op: unwrapped delegate, no traceparent emitted.
        var earlyChannel = new RecordingChannel();
        assertTrue(KrpcOtel.isNoop(), "krpc must start no-op before any install");
        ClientCall<InputProto, OutputProto> early =
                interceptor.interceptCall(MD, CallOptions.DEFAULT, earlyChannel);
        assertSame(earlyChannel.call, early, "pre-install call must be unwrapped (untraced)");
        early.start(new ClientCall.Listener<>() {}, new Metadata());
        assertTrue(traceparents(earlyChannel).isEmpty(), "pre-install call must emit no traceparent");

        // 2) Install an SDK later (simulating Quarkus registering after the first requests).
        var exporter = InMemorySpanExporter.create();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tp)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        KrpcOtel.install(sdk);
        assertFalse(KrpcOtel.isNoop(), "after install krpc must trace");

        // 3) Subsequent call traces: wrapped, one traceparent, a CLIENT span exported.
        var lateChannel = new RecordingChannel();
        ClientCall<InputProto, OutputProto> late =
                interceptor.interceptCall(MD, CallOptions.DEFAULT, lateChannel);
        assertNotSame(lateChannel.call, late, "post-install call must be wrapped (traced)");
        late.start(new ClientCall.Listener<>() {}, new Metadata());
        assertEquals(1, traceparents(lateChannel).size(), "post-install call must emit one traceparent");
        lateChannel.call.listener.onClose(Status.OK, new Metadata()); // end the CLIENT span
        assertEquals(1, exporter.getFinishedSpanItems().stream()
                        .filter(s -> s.getKind() == io.opentelemetry.api.trace.SpanKind.CLIENT).count(),
                "post-install call must export a CLIENT span");

        // 4) The JVM global was never pinned by core: a later set() still succeeds (no ISE).
        assertDoesNotThrow(() -> GlobalOpenTelemetry.set(sdk),
                "core must never pin GlobalOpenTelemetry — a consumer's own set() must still work");
    }

    private static List<String> traceparents(RecordingChannel ch) {
        List<String> out = new ArrayList<>();
        var all = ch.call.captured.getAll(TraceMeta.TRACEPARENT_KEY);
        if (all != null) {
            all.forEach(out::add);
        }
        return out;
    }
}
