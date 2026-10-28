package tech.krpc.examples.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.krpc.context.KrpcOtel;

/**
 * OTEL-001 B1d (ADR-0006): positive-injection container proof. Boots the quickstart — a DB-free
 * Quarkus consumer — WITH {@code quarkus-opentelemetry} on the test classpath (production deps
 * unchanged). Proves the explicit-injection path end to end:
 *
 * <ol>
 *   <li>the container's {@link OpenTelemetry} CDI bean is resolvable (the same bean
 *       {@code RpcServiceExpose}'s {@code Instance<OpenTelemetry>} sees at {@code @Startup});
 *   <li>{@code RpcServiceExpose} therefore ran {@link KrpcOtel#install} — {@link KrpcOtel#isNoop()}
 *       is false;
 *   <li>a real krpc gRPC call through the quickstart {@code Hello} service exports a SERVER span
 *       with the rpc semantic attributes.
 * </ol>
 *
 * <p>The gRPC call is made from {@link HelloGrpcCaller} (a CDI bean) so all {@code io.grpc} usage
 * runs in the Quarkus application classloader; the test class itself references no {@code io.grpc}
 * type, avoiding the @QuarkusTest split-classloader {@code LinkageError}.
 *
 * <p>Red-first: with the install wiring neutered (or the bean absent) step 2 flips true, the server
 * interceptor short-circuits, and the span assertion in step 3 goes red.
 */
@QuarkusTest
class OtelServerSpanQuarkusTest {

    @Inject
    RecordingSpanExporter spanExporter;

    @Inject
    Instance<OpenTelemetry> openTelemetry;

    @Inject
    HelloGrpcCaller grpcCaller;

    @BeforeEach
    void reset() {
        spanExporter.reset();
    }

    @Test
    void containerOpenTelemetryIsInstalled_andGrpcCallExportsServerSpan() throws Exception {
        // 1) The container's OpenTelemetry bean is resolvable (same bean RpcServiceExpose injects).
        assertTrue(openTelemetry.isResolvable(),
                "quarkus-opentelemetry must expose a resolvable OpenTelemetry CDI bean");

        // 2) RpcServiceExpose@Startup installed it into krpc — no global probing, explicit inject.
        assertFalse(KrpcOtel.isNoop(),
                "RpcServiceExpose must have installed the container OpenTelemetry into krpc at startup");

        // 3) A real krpc gRPC call to the quickstart Hello service (over the wire, port 50051).
        int code = grpcCaller.callHello("otel-quarkus");
        assertEquals(0, code, "the gRPC call must succeed (RpcResult code 0)");

        SpanData server = awaitServerSpan();
        assertTrue(server.getName().endsWith("/hello"),
                "SERVER span name must be the full gRPC method, got " + server.getName());
        assertEquals("grpc", server.getAttributes().get(KrpcOtel.RPC_SYSTEM));
        assertEquals("hello", server.getAttributes().get(KrpcOtel.RPC_METHOD));
    }

    private SpanData awaitServerSpan() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (SpanData s : spanExporter.getFinishedSpanItems()) {
                if (s.getKind() == SpanKind.SERVER && s.getName().endsWith("/hello")) {
                    return s;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no SERVER span for the Hello call was exported; spans="
                + spanExporter.getFinishedSpanItems());
    }
}
