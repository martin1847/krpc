package tech.krpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import tech.krpc.annotation.RpcService;
import tech.krpc.client.RpcClientFactory;
import tech.krpc.context.KrpcOtel;
import tech.krpc.context.TraceMeta;
import tech.krpc.model.RpcResult;

/**
 * OTEL-002 R2-2 (no OTel SDK): the gRPC face ({@code ServerContext}) must validate the inbound W3C
 * traceparent BEFORE writing any trace MDC, so a malformed inbound context is NOT forwarded on the
 * outbound krpc hop (only derived log IDs were suppressed before). Runs on the SDK-free classpath so
 * propagation is pure ADR-0003 MDC forwarding ({@code PropagateTraceCall}), not OTel injection.
 *
 * <p>Topology: the test seeds its own MDC (simulating an inbound caller), calls Order-service A over
 * the wire; A's handler calls Ledger-service B. B records the traceparent it actually received. A's
 * {@code ServerContext} is the face under test.
 */
class ServerTraceMdcPropagationTest {

    static final String APP = "otel-grpc-mdc-it";

    @RpcService("Ledger")
    public interface LedgerService {
        RpcResult<String> record(String note);
    }

    @RpcService("Order")
    public interface OrderService {
        RpcResult<String> place(String note);
    }

    public static final class LedgerServiceImpl implements LedgerService {
        final AtomicInteger count = new AtomicInteger(-1);
        final AtomicReference<String> value = new AtomicReference<>();

        @Override
        public RpcResult<String> record(String note) {
            var all = ServerContext.current().getHeaders().getAll(TraceMeta.TRACEPARENT_KEY);
            int n = 0;
            String last = null;
            if (all != null) {
                for (String v : all) {
                    n++;
                    last = v;
                }
            }
            count.set(n);
            value.set(last);
            return RpcResult.ok("ledgered:" + note);
        }
    }

    public static final class OrderServiceImpl implements OrderService {
        private final LedgerService ledger;

        OrderServiceImpl(LedgerService ledger) {
            this.ledger = ledger;
        }

        @Override
        public RpcResult<String> place(String note) {
            return RpcResult.ok("ordered:" + ledger.record(note).getData());
        }
    }

    private Server serverA;
    private Server serverB;
    private ManagedChannel channelA;
    private ManagedChannel channelB;
    private ExecutorService execA;
    private ExecutorService execB;
    private LedgerServiceImpl ledgerImpl;
    private OrderService orderClient;

    @BeforeEach
    void setUp() throws Exception {
        KrpcOtel.install(io.opentelemetry.api.OpenTelemetry.noop());

        int portB = freePort();
        execB = Executors.newVirtualThreadPerTaskExecutor();
        ledgerImpl = new LedgerServiceImpl();
        serverB = new RpcServerBuilder.Builder(APP, portB).executor(execB)
                .addService(ledgerImpl).build().startServer();
        channelB = ManagedChannelBuilder.forAddress("127.0.0.1", portB).usePlaintext().build();
        LedgerService ledgerClient = new RpcClientFactory(APP, channelB).get(LedgerService.class);

        int portA = freePort();
        execA = Executors.newVirtualThreadPerTaskExecutor();
        serverA = new RpcServerBuilder.Builder(APP, portA).executor(execA)
                .addService(new OrderServiceImpl(ledgerClient)).build().startServer();
        channelA = ManagedChannelBuilder.forAddress("127.0.0.1", portA).usePlaintext().build();
        orderClient = new RpcClientFactory(APP, channelA).get(OrderService.class);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (channelA != null) channelA.shutdownNow();
        if (channelB != null) channelB.shutdownNow();
        if (serverA != null) serverA.shutdownNow();
        if (serverB != null) serverB.shutdownNow();
        if (execA != null) execA.shutdownNow();
        if (execB != null) execB.shutdownNow();
        KrpcOtel.install(io.opentelemetry.api.OpenTelemetry.noop());
    }

    /** Seed the caller MDC, drive A over the wire, and report what B received. */
    private void placeWithInbound(String inboundTraceparent) {
        MDC.put(TraceMeta.MDC_TRACEPARENT, inboundTraceparent);
        try {
            assertTrue(orderClient.place("x").isOk());
        } finally {
            MDC.clear();
        }
    }

    @Test
    void validInbound_forwardedVerbatim() {
        String tp = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        placeWithInbound(tp);
        assertEquals(1, ledgerImpl.count.get());
        assertEquals(tp, ledgerImpl.value.get());
    }

    @Test
    void malformedInbound_zeroForwarded() {
        // R2-2: A receives a malformed traceparent; ServerContext must NOT bind it, so A->B carries none.
        placeWithInbound("00-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz-0000000000000000-xx");
        assertEquals(0, ledgerImpl.count.get(),
                "malformed gRPC inbound traceparent must not be forwarded downstream");
    }

    @Test
    void futureVersionWithExtension_forwardedVerbatim() {
        // R2-1 + R2-2: a valid higher version with an opaque trailing field is bound and forwarded whole.
        String tp = "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01-cafebabe";
        placeWithInbound(tp);
        assertEquals(1, ledgerImpl.count.get());
        assertEquals(tp, ledgerImpl.value.get(), "future-version traceparent forwarded verbatim");
    }

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
