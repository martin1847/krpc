package tech.krpc.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.handler.codec.http.HttpHeaders;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
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

import tech.krpc.context.KrpcOtel;

/**
 * OTEL-001 (ADR-0006): the HTTP handler's SERVER span. Boots a real in-process {@link HttpServer}
 * and drives it with {@link HttpClient}, asserting that:
 *
 * <ul>
 *   <li>an inbound W3C {@code traceparent} is extracted and the handler's SERVER span becomes its
 *       child (same trace id, parent = the inbound span id);
 *   <li>the span is current on the handler thread (a virtual thread);
 *   <li>a request with no inbound context still gets a fresh root SERVER span.
 * </ul>
 */
class HttpOtelSpanTest {

    private int port;
    private TraceHandler handler;
    private HttpServer server;
    private HttpClient client;
    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;

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

        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        handler = new TraceHandler();
        server = new HttpServer(handler, port);
        server.start();
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.shutdown();
        }
        if (tracerProvider != null) {
            tracerProvider.shutdown();
        }
        KrpcOtel.install(OpenTelemetry.noop());
    }

    @Test
    void inboundTraceparent_becomesServerSpanParent() throws Exception {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String parentSpanId = "b7ad6b7169203331";
        String traceparent = "00-" + traceId + "-" + parentSpanId + "-01";

        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/trace"))
                        .header("Content-Type", "application/json")
                        .header("traceparent", traceparent)
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());

        SpanData span = awaitServerSpan();
        assertEquals(traceId, span.getTraceId(), "SERVER span must join the inbound trace");
        assertEquals(parentSpanId, span.getParentSpanId(),
                "SERVER span must be a child of the inbound traceparent span");
        assertEquals("/trace", span.getName(), "span name is the handler path");
        assertEquals(SpanKind.SERVER, span.getKind());
        assertEquals(200L, span.getAttributes().get(KrpcOtel.HTTP_RESPONSE_STATUS_CODE));

        // Scope propagated to the handler's virtual thread.
        assertTrue(handler.threadVirtual.get(), "handler must run on a virtual thread");
        assertNotNull(handler.observed.get());
        assertTrue(handler.observed.get().isValid(), "the SERVER span must be current in the handler");
        assertEquals(span.getSpanId(), handler.observed.get().getSpanId());
    }

    @Test
    void noInboundContext_createsRootServerSpan() throws Exception {
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/trace"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());

        SpanData span = awaitServerSpan();
        assertTrue(span.getSpanContext().isValid(), "a fresh root SERVER span must be created");
        assertFalse(span.getParentSpanContext().isValid(),
                "with no inbound context the SERVER span is a trace root");
    }

    private SpanData awaitServerSpan() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            List<SpanData> spans = exporter.getFinishedSpanItems();
            if (!spans.isEmpty()) {
                return spans.get(0);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no SERVER span was exported");
    }

    /** A handler that records the current span + thread kind during handle(). */
    static final class TraceHandler extends AbstractHttpHandler {
        final AtomicReference<SpanContext> observed = new AtomicReference<>();
        final AtomicBoolean threadVirtual = new AtomicBoolean(false);

        TraceHandler() {
            postMap.put("/trace", new PostHandler<String>() {
                @Override
                public Class<String> getParamClass() {
                    return String.class;
                }

                @Override
                public String path() {
                    return "/trace";
                }

                @Override
                public boolean useValidator() {
                    return false;
                }

                @Override
                public byte[] handle(String param, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
                    threadVirtual.set(Thread.currentThread().isVirtual());
                    observed.set(Span.current().getSpanContext());
                    return "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
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
