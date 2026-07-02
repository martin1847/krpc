package test.krpc.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.krpc.client.RpcClientFactory;
import tech.krpc.model.RpcResult;
import tech.krpc.server.RpcServerBuilder;
import tech.krpc.server.exe.ThreadPool;

/**
 * Integration test for {@code RpcServerBuilder.Builder.maxConcurrentCallsPerConnection(N)} — the
 * per-HTTP/2-connection concurrent-stream cap (D2 / CVE-2026-47244 defence-in-depth).
 *
 * <p>Self-provisioning like {@code test.krpc.auth.GrpcContextAuthIT}: stands up the production
 * gRPC server on a loopback port with the production virtual-thread executor and a small cap,
 * then drives it with a real {@code ManagedChannel}. No MySQL, no Quarkus boot; the only socket
 * traffic is loopback.
 *
 * <p>The contract under test is HTTP/2 SETTINGS_MAX_CONCURRENT_STREAMS: the server advertises
 * {@code N} to the client, and grpc-netty QUEUES streams beyond {@code N} on the client rather
 * than failing them. So an over-cap call is not rejected — it waits until a slot frees, then
 * runs normally.
 *
 * <p>Cap is set to {@code N=2} and {@code N+1=3} calls are fired concurrently on ONE channel for
 * a fast, deterministic proof. The production goal's "2001st of 2000" scenario is the identical
 * code path — the same client-side stream queuing — just at a larger number; N=2 exercises the
 * same queuing semantics without needing thousands of threads.
 */
class MaxConcurrentCallsIT {

    static final String APP = "stream-cap-it";
    static final int CAP = 2; // maxConcurrentCallsPerConnection
    static final int CALLS = CAP + 1; // fire one over the cap

    Server rpcServer;
    int rpcPort;
    ExecutorService serverExecutor; // production VT executor the server runs bodies on
    ExecutorService clientPool; // fires the concurrent client calls
    ManagedChannel channel;
    BlockingCapServiceImpl impl;

    @BeforeEach
    void setUp() throws Exception {
        rpcPort = freePort();
        serverExecutor = ThreadPool.newExecutor(APP, 6);
        // Server-side executor is virtual-thread-per-task (unbounded): it can NOT be the thing
        // that limits concurrency. The only bound is the connection cap — so if the cap were
        // absent, all CALLS bodies would run at once. That is what makes the high-water assertion
        // a real guard rather than an accident of thread starvation.
        impl = new BlockingCapServiceImpl(CAP); // entered latch trips once CAP requests are blocked
        rpcServer = new RpcServerBuilder.Builder(APP, rpcPort)
                .executor(serverExecutor)
                .maxConcurrentCallsPerConnection(CAP)
                .addService(impl)
                .build()
                .startServer();

        // Single channel == single HTTP/2 connection: the cap is per-connection, so all CALLS
        // calls contend for the same N stream slots.
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", rpcPort)
                .usePlaintext()
                .build();

        clientPool = Executors.newFixedThreadPool(CALLS);
    }

    @AfterEach
    void tearDown() {
        // Open the gate first so any request still blocked in the service body can drain, then
        // tear down. releaseAll is idempotent, so calling it here is safe even if the test
        // already released.
        if (impl != null) impl.releaseAll();
        if (clientPool != null) clientPool.shutdownNow();
        if (channel != null) channel.shutdownNow();
        if (rpcServer != null) rpcServer.shutdownNow();
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    void overCapCallIsQueuedNotRejected_andConcurrencyStaysBounded() throws Exception {
        var svc = new RpcClientFactory(APP, channel).get(BlockingCapService.class);

        // Fire CALLS (=3) unary calls concurrently on the one channel. Each blocks server-side
        // until we open the gate. Two "started" barriers let us confirm both client threads have
        // actually issued their call before we wait on server-side entry.
        CountDownLatch clientStarted = new CountDownLatch(CALLS);
        List<Future<RpcResult<Integer>>> futures = new ArrayList<>();
        for (int i = 0; i < CALLS; i++) {
            futures.add(clientPool.submit(() -> {
                clientStarted.countDown();
                return svc.occupy();
            }));
        }
        assertTrue(clientStarted.await(10, TimeUnit.SECONDS),
                "all client call threads should have been scheduled");

        // Wait until CAP (=2) requests are actually blocked inside the service body. Under the
        // cap this is guaranteed: the client opens exactly CAP streams and queues the rest. If
        // this await ever times out, the cap starved us below N (a regression in the other
        // direction), so it is an assertion, not a silent skip.
        assertTrue(impl.awaitEntered(10, TimeUnit.SECONDS),
                "expected " + CAP + " concurrent requests to enter the service body");

        // (b) THE MEANINGFUL ASSERTION. With CAP streams blocked and the (CAP+1)th still queued
        // client-side, the peak concurrency the server ever saw must be <= CAP. If the cap were
        // removed, the client would open all CALLS streams at once; they would all pile up on the
        // release gate (which we still hold), and the high-water mark would climb to CALLS (=3).
        // A pure "all calls succeed" check would pass even with no cap — this is what proves the
        // cap actually bounded the in-flight streams.
        assertTrue(impl.highWater() <= CAP,
                () -> "high-water concurrency " + impl.highWater() + " exceeded cap " + CAP
                        + " — the per-connection stream cap did not bound in-flight streams");
        // Exact peak: the two admitted streams are both blocked right now.
        assertEquals(CAP, impl.highWater(),
                "expected exactly the cap in flight while the over-cap call is queued");

        // Release the gate; the two blocked calls return, freeing slots so the queued (CAP+1)th
        // call opens its stream and completes.
        impl.releaseAll();

        // (a) & (c): every call — including the over-cap one — returns a normal successful result.
        // No StatusRuntimeException: the excess stream was QUEUED (backpressure), never rejected.
        int oks = 0;
        for (int i = 0; i < CALLS; i++) {
            RpcResult<Integer> res;
            try {
                res = futures.get(i).get(10, TimeUnit.SECONDS);
            } catch (ExecutionException ee) {
                if (ee.getCause() instanceof StatusRuntimeException sre) {
                    fail("over-cap call #" + i + " was REJECTED with " + sre.getStatus()
                            + " — maxConcurrentCallsPerConnection must queue (backpressure), not reject");
                }
                throw ee;
            }
            assertTrue(res.isOk(),
                    () -> "call returned an error instead of being queued: code=" + res.getCode()
                            + " msg=" + res.getMsg());
            oks++;
        }
        assertEquals(CALLS, oks, "all " + CALLS + " calls must ultimately succeed");

        // Defensive: even after all calls drained, the peak never exceeded the cap. Post-release
        // the (CAP+1)th runs after the first two have already decremented, so it never overlaps
        // beyond CAP — final high-water stays == CAP under the cap, but would be CALLS without it.
        assertTrue(impl.highWater() <= CAP,
                () -> "final high-water " + impl.highWater() + " exceeded cap " + CAP);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
