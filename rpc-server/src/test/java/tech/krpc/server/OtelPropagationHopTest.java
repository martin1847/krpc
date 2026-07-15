package tech.krpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import tech.krpc.context.KrpcOtel;

/**
 * OTEL-001 (ADR-0006): end-to-end trace propagation across a real in-process gRPC hop, using the
 * production {@link OtelServerInterceptor} and {@code tech.krpc.client.OtelClientInterceptor} on a
 * generic echo service. Defends the core contract:
 *
 * <ul>
 *   <li>a root (inbound) context → CLIENT span (child) → injected {@code traceparent} → SERVER span
 *       (child of the CLIENT span), all sharing one trace id — the P→C→S parent chain;
 *   <li>the SERVER span is current on the handler thread (a virtual thread), so context reaches the
 *       handler and any outbound call it makes.
 * </ul>
 */
class OtelPropagationHopTest {

    private static final MethodDescriptor.Marshaller<String> STRING_MARSHALLER =
            new MethodDescriptor.Marshaller<>() {
                @Override
                public InputStream stream(String value) {
                    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public String parse(InputStream stream) {
                    try {
                        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            };

    private static final MethodDescriptor<String, String> ECHO =
            MethodDescriptor.<String, String>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("krpc.test.Echo/echo")
                    .setRequestMarshaller(STRING_MARSHALLER)
                    .setResponseMarshaller(STRING_MARSHALLER)
                    .build();

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetrySdk sdk;
    private Server server;
    private ManagedChannel channel;
    private ExecutorService serverExecutor;

    private final AtomicReference<SpanContext> handlerSpan = new AtomicReference<>();
    private final AtomicBoolean handlerThreadVirtual = new AtomicBoolean(false);

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

        // Mirror production: the handler runs on the app executor (a virtual thread; ADR-0002).
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();

        ServerServiceDefinition service = ServerServiceDefinition.builder("krpc.test.Echo")
                .addMethod(ECHO, ServerCalls.asyncUnaryCall((req, obs) -> {
                    handlerThreadVirtual.set(Thread.currentThread().isVirtual());
                    handlerSpan.set(Span.current().getSpanContext());
                    obs.onNext(req);
                    obs.onCompleted();
                }))
                .build();

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .executor(serverExecutor)
                .intercept(new OtelServerInterceptor())
                .addService(service)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        if (tracerProvider != null) {
            tracerProvider.shutdown();
        }
        KrpcOtel.install(OpenTelemetry.noop());
    }

    @Test
    void inboundContextFlowsThroughClientAndServerSpans() throws Exception {
        Channel traced = ClientInterceptors.intercept(channel, new tech.krpc.client.OtelClientInterceptor());

        Span root = sdk.getTracer("test")
                .spanBuilder("inbound-root").setSpanKind(SpanKind.SERVER).startSpan();
        String response;
        try (Scope scope = root.makeCurrent()) {
            response = ClientCalls.blockingUnaryCall(traced, ECHO, CallOptions.DEFAULT, "ping");
        } finally {
            root.end();
        }
        assertEquals("ping", response, "echo must round-trip the payload");

        // The SERVER span ends asynchronously on the server side; await all three spans.
        List<SpanData> spans = awaitSpans(3);

        SpanData clientSpan = spanOfKind(spans, SpanKind.CLIENT);
        SpanData serverSpan = spanOfKind(spans, SpanKind.SERVER, "krpc.test.Echo/echo");

        // One trace across the whole hop.
        assertEquals(root.getSpanContext().getTraceId(), clientSpan.getTraceId(),
                "CLIENT span must join the inbound trace");
        assertEquals(root.getSpanContext().getTraceId(), serverSpan.getTraceId(),
                "SERVER span must join the inbound trace (traceparent extracted)");

        // Parent chain: root -> client -> server.
        assertEquals(root.getSpanContext().getSpanId(), clientSpan.getParentSpanId(),
                "CLIENT span must be a child of the inbound span");
        assertEquals(clientSpan.getSpanId(), serverSpan.getParentSpanId(),
                "SERVER span must be a child of the CLIENT span (propagation across the hop)");

        // rpc semantic conventions present.
        assertEquals("grpc", serverSpan.getAttributes().get(KrpcOtel.RPC_SYSTEM));
        assertEquals("krpc.test.Echo", serverSpan.getAttributes().get(KrpcOtel.RPC_SERVICE));
        assertEquals("echo", serverSpan.getAttributes().get(KrpcOtel.RPC_METHOD));

        // Scope propagated to the handler's virtual thread.
        assertTrue(handlerThreadVirtual.get(), "handler must run on a virtual thread (ADR-0002)");
        assertNotNull(handlerSpan.get());
        assertTrue(handlerSpan.get().isValid(), "the SERVER span must be current inside the handler");
        assertEquals(serverSpan.getSpanId(), handlerSpan.get().getSpanId(),
                "the span current in the handler must be the SERVER span");
    }

    private List<SpanData> awaitSpans(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (exporter.getFinishedSpanItems().size() >= count) {
                return exporter.getFinishedSpanItems();
            }
            Thread.sleep(20);
        }
        return exporter.getFinishedSpanItems();
    }

    private static SpanData spanOfKind(List<SpanData> spans, SpanKind kind) {
        return spans.stream().filter(s -> s.getKind() == kind).findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " span exported; got " + spans));
    }

    private static SpanData spanOfKind(List<SpanData> spans, SpanKind kind, String name) {
        return spans.stream().filter(s -> s.getKind() == kind && s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " span '" + name + "' in " + spans));
    }
}
