package tech.krpc.server;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;

import tech.krpc.context.KrpcOtel;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;

/**
 * OTEL-001 B1 (ADR-0006): the server interceptor's no-SDK fast path, on a genuinely SDK-free
 * classpath (this source set carries {@code opentelemetry-api} only). Asserts {@link KrpcOtel#isNoop()}
 * and that {@link OtelServerInterceptor} returns the delegate listener <b>unwrapped</b> — the
 * short-circuit branch, observed by identity, so no span/scope/listener-wrapper is allocated.
 */
class NoSdkServerFastPathTest {

    /** Minimal ServerCall stub; the no-op path never touches these (it delegates before reading them). */
    private static final class StubServerCall extends ServerCall<InputProto, OutputProto> {
        @Override public void request(int numMessages) {}
        @Override public void sendHeaders(Metadata headers) {}
        @Override public void sendMessage(OutputProto message) {}
        @Override public void close(Status status, Metadata trailers) {}
        @Override public boolean isCancelled() { return false; }
        @Override public MethodDescriptor<InputProto, OutputProto> getMethodDescriptor() {
            throw new AssertionError("no-op fast path must not read the method descriptor");
        }
    }

    @Test
    void isNoopIsTrueWithNoSdk() {
        assertTrue(KrpcOtel.isNoop(),
                "with no OTel SDK on the classpath, the server interceptor must see a no-op TracerProvider");
    }

    @Test
    void serverInterceptorReturnsListenerUnwrapped_shortCircuitTaken() {
        ServerCall.Listener<InputProto> sentinel = new ServerCall.Listener<>() {};
        ServerCallHandler<InputProto, OutputProto> next = (call, headers) -> sentinel;

        ServerCall.Listener<InputProto> result =
                new OtelServerInterceptor().interceptCall(new StubServerCall(), new Metadata(), next);

        // The no-op branch returns next.startCall(...) directly — the exact sentinel, never a
        // TracingServerCallListener wrapper. Identity proves the short-circuit was taken; the
        // StubServerCall.getMethodDescriptor() guard would fail loudly if the span path ran.
        assertSame(sentinel, result,
                "no-SDK server interceptor must return the raw listener unwrapped (no span-creating wrapper)");
    }
}
