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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.netty.handler.codec.http.HttpHeaders;
import io.opentelemetry.api.OpenTelemetry;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.krpc.annotation.RpcService;
import tech.krpc.client.RpcClientFactory;
import tech.krpc.context.KrpcOtel;
import tech.krpc.context.TraceMeta;
import tech.krpc.model.RpcResult;
import tech.krpc.server.RpcServerBuilder;
import tech.krpc.server.ServerContext;

/**
 * OTEL-002 Fix 1 (field defect #1), ADR-0003 path with <b>no OTel SDK installed</b>: the HTTP
 * webhook/callback face must forward the inbound W3C {@code traceparent} to an outbound krpc client
 * call exactly like the gRPC face does (via MDC + {@code PropagateTraceCall}). On staging rc1 the
 * HTTP handler never bound the inbound trace into the handler body, so the downstream call carried
 * no {@code traceparent} and started a new trace. This reproduces that break deterministically
 * without any SDK: propagation must not depend on span creation.
 */
class HttpTraceMdcPropagationTest {

    static final String APP = "otel-http-mdc-it";

    @RpcService("Ledger")
    public interface LedgerService {
        RpcResult<String> record(String note);
    }

    public static final class LedgerServiceImpl implements LedgerService {
        final AtomicInteger inboundTraceparentCount = new AtomicInteger(-1);
        final AtomicReference<String> inboundTraceparent = new AtomicReference<>();

        @Override
        public RpcResult<String> record(String note) {
            var headers = ServerContext.current().getHeaders();
            var all = headers.getAll(TraceMeta.TRACEPARENT_KEY);
            int n = 0;
            String last = null;
            if (all != null) {
                for (String v : all) {
                    n++;
                    last = v;
                }
            }
            inboundTraceparentCount.set(n);
            inboundTraceparent.set(last);
            return RpcResult.ok("ledgered:" + note);
        }
    }

    private Server serverB;
    private ManagedChannel channelB;
    private ExecutorService execB;
    private LedgerServiceImpl ledgerImpl;
    private HttpServer httpServer;
    private int httpPort;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        // No SDK: span creation is a no-op, so trace continuity depends solely on ADR-0003 MDC
        // forwarding — the same mechanism the gRPC face uses.
        KrpcOtel.install(OpenTelemetry.noop());

        int portB = freePort();
        execB = Executors.newVirtualThreadPerTaskExecutor();
        ledgerImpl = new LedgerServiceImpl();
        serverB = new RpcServerBuilder.Builder(APP, portB).executor(execB)
                .addService(ledgerImpl).build().startServer();
        channelB = ManagedChannelBuilder.forAddress("127.0.0.1", portB).usePlaintext().build();
        LedgerService ledgerClient = new RpcClientFactory(APP, channelB).get(LedgerService.class);

        httpPort = freePort();
        httpServer = new HttpServer(new CallbackHandler(ledgerClient), httpPort);
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
        KrpcOtel.install(OpenTelemetry.noop());
    }

    @Test
    void validInboundTraceparent_forwardedExactlyOnce() throws Exception {
        String tp = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        post(b -> b.header("traceparent", tp));
        assertEquals(1, ledgerImpl.inboundTraceparentCount.get(),
                "valid inbound traceparent forwarded exactly once");
        assertEquals(tp, ledgerImpl.inboundTraceparent.get(),
                "downstream receives the same traceparent verbatim (ADR-0003)");
    }

    @Test
    void absentInboundTraceparent_zeroOutbound() throws Exception {
        post(b -> b);
        assertEquals(0, ledgerImpl.inboundTraceparentCount.get(),
                "no inbound traceparent -> nothing forwarded (no-SDK, no span creation)");
    }

    @Test
    void malformedInboundTraceparent_zeroOutbound() throws Exception {
        // R1-4: non-hex trace id + all-zero span id + non-hex flags -> strictly rejected, never sent.
        post(b -> b.header("traceparent", "00-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz-0000000000000000-xx"));
        assertEquals(0, ledgerImpl.inboundTraceparentCount.get(),
                "malformed inbound traceparent must not be forwarded (zero outbound)");
    }

    @Test
    void duplicateInboundTraceparent_exactlyOneOutbound() throws Exception {
        String tp1 = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        String tp2 = "00-11111111111111111111111111111111-2222222222222222-01";
        post(b -> b.header("traceparent", tp1).header("traceparent", tp2));
        assertEquals(1, ledgerImpl.inboundTraceparentCount.get(),
                "a duplicate traceparent header must still yield exactly one outbound value");
    }

    @Test
    void futureVersionFourField_forwardedVerbatim() throws Exception {
        // R2-1: a valid future (non-ff) version with four fields is accepted and forwarded verbatim.
        String tp = "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        post(b -> b.header("traceparent", tp));
        assertEquals(1, ledgerImpl.inboundTraceparentCount.get());
        assertEquals(tp, ledgerImpl.inboundTraceparent.get(), "version-01 forwarded verbatim");
    }

    @Test
    void futureVersionWithExtension_forwardedVerbatim() throws Exception {
        // R2-1: a higher version MAY carry opaque trailing fields — accept + forward the whole
        // header unchanged (no continuity loss on the no-SDK path).
        String tp = "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01-cafebabe";
        post(b -> b.header("traceparent", tp));
        assertEquals(1, ledgerImpl.inboundTraceparentCount.get());
        assertEquals(tp, ledgerImpl.inboundTraceparent.get(),
                "future-version extension forwarded verbatim, opaque trailing kept");
    }

    @Test
    void versionFf_rejected_zeroOutbound() throws Exception {
        // R2-1: version ff is reserved/invalid — never forwarded.
        post(b -> b.header("traceparent",
                "ff-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"));
        assertEquals(0, ledgerImpl.inboundTraceparentCount.get(), "version ff must be rejected");
    }

    @Test
    void version00WithTrailingField_rejected() throws Exception {
        // R2-1: version 00 is exactly four fields — a trailing segment is invalid.
        post(b -> b.header("traceparent",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01-extra"));
        assertEquals(0, ledgerImpl.inboundTraceparentCount.get(),
                "version 00 with a trailing field must be rejected");
    }

    private void post(java.util.function.UnaryOperator<HttpRequest.Builder> headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/callback"))
                .header("Content-Type", "application/json");
        HttpResponse<String> r = client.send(
                headers.apply(b).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), () -> "callback must succeed: " + r.body());
    }

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    /** HTTP handler whose body performs an outbound krpc client call, like a webhook callback. */
    static final class CallbackHandler extends AbstractHttpHandler {
        private final LedgerService ledger;

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
