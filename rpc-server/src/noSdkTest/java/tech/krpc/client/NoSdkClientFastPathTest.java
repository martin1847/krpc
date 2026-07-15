package tech.krpc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
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

import tech.krpc.annotation.RpcService;
import tech.krpc.common.MethodStub;
import tech.krpc.context.KrpcOtel;
import tech.krpc.context.TraceMeta;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;
import tech.krpc.internal.SerialEnum;
import tech.krpc.model.RpcResult;

/**
 * OTEL-001 B1 (ADR-0006): proves the no-SDK fast path on a <b>genuinely SDK-free classpath</b> —
 * this source set's runtimeClasspath carries {@code opentelemetry-api} only, no
 * {@code opentelemetry-sdk}/{@code sdk-testing} (unlike the regular test suite, whose no-op relied
 * on {@code resetForTest()} while the SDK was still on the classpath).
 *
 * <p>Asserts: the SDK really is absent; {@link KrpcOtel#isNoop()} is true; the client interceptor
 * returns the delegate <b>unwrapped</b> (short-circuit branch taken — a cheap identity observable,
 * not timing); and the outbound headers are byte-identical to the pre-OTEL ADR-0003 MDC behavior.
 */
class NoSdkClientFastPathTest {

    @RpcService("NoSdkRpc")
    interface NoSdkRpc {
        RpcResult<String> echo(String s);
    }

    private static final class RecordingClientCall<Req, Resp> extends ClientCall<Req, Resp> {
        volatile Metadata captured;

        @Override public void start(Listener<Resp> l, Metadata headers) { this.captured = headers; }
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

    @BeforeEach
    void clearMdc() { MDC.clear(); }

    @AfterEach
    void afterMdc() { MDC.clear(); }

    @Test
    void otelSdkArtifactsAreAbsentFromClasspath() {
        // The whole point of this source set: no SDK on the classpath (not merely reset-to-noop).
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.opentelemetry.sdk.OpenTelemetrySdk"),
                "opentelemetry-sdk must NOT be on this test classpath");
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter"),
                "opentelemetry-sdk-testing must NOT be on this test classpath");
    }

    @Test
    void isNoopIsTrueWithNoSdk() {
        assertTrue(KrpcOtel.isNoop(),
                "with no OTel SDK on the classpath, GlobalOpenTelemetry must resolve to a no-op TracerProvider");
    }

    @Test
    void clientInterceptorReturnsDelegateUnwrapped_shortCircuitTaken() {
        RecordingChannel channel = new RecordingChannel();
        MethodDescriptor<InputProto, OutputProto> md = MethodStub.buildMd("NoSdkRpc/echo");

        ClientCall<InputProto, OutputProto> result =
                new OtelClientInterceptor().interceptCall(md, CallOptions.DEFAULT, channel);

        // The no-op branch returns next.newCall(...) directly — the exact recorded delegate,
        // never a TracingClientCall wrapper. Identity proves the short-circuit was taken.
        assertSame(channel.call, result,
                "no-SDK client interceptor must return the raw call unwrapped (no span-creating wrapper)");
    }

    @Test
    void makeCall_noMdc_noTraceparent() throws Exception {
        var invoker = invoker();
        invoker.makeCall(CallOptions.DEFAULT).start(new ClientCall.Listener<>() {}, new Metadata());
        assertTrue(traceparents().isEmpty(),
                "no SDK + no MDC → no traceparent emitted");
    }

    @Test
    void makeCall_mdcTraceparent_forwardedAsSingleHeader() throws Exception {
        String parent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        MDC.put(TraceMeta.MDC_TRACEPARENT, parent);

        var invoker = invoker();
        invoker.makeCall(CallOptions.DEFAULT).start(new ClientCall.Listener<>() {}, new Metadata());

        assertEquals(List.of(parent), traceparents(),
                "no SDK + MDC traceparent → exactly the MDC value on the wire (ADR-0003 byte parity)");
    }

    private RecordingChannel channel;

    private MethodCallProxyHandler<NoSdkRpc>.ChannelMethodInvoker invoker() throws Exception {
        channel = new RecordingChannel();
        var handler = new MethodCallProxyHandler<>(
                "no-sdk", channel, NoSdkRpc.class, List.of(), null, SerialEnum.JSON);
        Method echo = NoSdkRpc.class.getMethod("echo", String.class);
        var inv = handler.stubMap.get(echo);
        assertNotNull(inv, "echo must have a wired invoker");
        return inv;
    }

    private List<String> traceparents() {
        assertNotNull(channel.call.captured, "the call must have been started");
        List<String> out = new ArrayList<>();
        var all = channel.call.captured.getAll(TraceMeta.TRACEPARENT_KEY);
        if (all != null) {
            all.forEach(out::add);
        }
        return out;
    }
}
