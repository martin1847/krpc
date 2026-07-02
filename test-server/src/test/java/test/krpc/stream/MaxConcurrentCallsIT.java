package test.krpc.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        // (1) Fire exactly CAP calls and wait until ALL of them are confirmed parked inside the
        // service body. entered is a CountDownLatch(CAP) counted down from within occupy(), so
        // awaitEntered only trips once CAP request bodies are actually running and blocked on the
        // release gate. If it times out the cap starved us below N (a regression the other way),
        // so it is an assertion, not a silent skip.
        List<Future<RpcResult<Integer>>> futures = new ArrayList<>();
        for (int i = 0; i < CAP; i++) {
            futures.add(clientPool.submit(svc::occupy));
        }
        assertTrue(impl.awaitEntered(10, TimeUnit.SECONDS),
                "expected " + CAP + " concurrent requests to enter and park in the service body");
        assertEquals(CAP, impl.highWater(),
                "expected exactly the cap in flight once all CAP calls are parked");

        // (2) NOW fire the over-cap (CAP+1'th) call on the SAME channel. All CAP stream slots on
        // the one HTTP/2 connection are held by the parked calls, so grpc-netty cannot open a
        // stream for this one — it must queue it at the transport (SETTINGS_MAX_CONCURRENT_STREAMS).
        // We confirm the client thread actually ran and issued the call before watching, so the
        // bounded wait below measures a call that HAS been dispatched, not one still sitting in
        // the executor queue.
        CountDownLatch overCapStarted = new CountDownLatch(1);
        Future<RpcResult<Integer>> overCap = clientPool.submit(() -> {
            overCapStarted.countDown();
            return svc.occupy();
        });
        futures.add(overCap);
        assertTrue(overCapStarted.await(10, TimeUnit.SECONDS),
                "over-cap client thread should have been scheduled and issued its call");

        // (3) THE RACE-KILLER: a BOUNDED WAIT instead of a single-instant peek. The old test read
        // high-water once right after awaitEntered — but at that instant the (CAP+1)th call might
        // simply not have arrived yet even with NO cap, so a broken cap could slip through as a
        // false green. Here we poll high-water for a window far longer than loopback stream-open
        // latency (microseconds). With the cap absent the over-cap stream would open, its body
        // would enter occupy(), and high-water would climb to CAP+1 well within this window — and
        // because we assert on EVERY poll across the WHOLE window, the poll is guaranteed to
        // observe the CAP+1 and go red. Staying pinned at CAP for the entire window is only
        // possible if the excess stream is genuinely parked at the transport. Under the cap the
        // CAP parked calls hold in-flight at exactly CAP (blocked on a gate we control), so this
        // window is deterministically green — no timing race in either direction.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (System.nanoTime() < deadline) {
            final int hw = impl.highWater();
            assertEquals(CAP, hw,
                    () -> "high-water reached " + hw + " while the over-cap call was outstanding — "
                            + "the per-connection stream cap failed to park the excess stream at "
                            + "the transport (expected it pinned at " + CAP + ")");
            // While the gate is held no occupy() can return; if the over-cap future has completed
            // it was REJECTED, not queued — the backpressure contract (queue, never reject) broke.
            assertFalse(overCap.isDone(),
                    "over-cap call settled while the release gate was still held — it must be "
                            + "parked at the transport (backpressure), not rejected");
            Thread.sleep(20);
        }

        // (4) Open the gate. The CAP parked calls return, freeing stream slots, so the queued
        // over-cap call finally opens its stream and completes.
        impl.releaseAll();

        // Every call — including the over-cap one — returns a normal successful result. No
        // StatusRuntimeException: the excess stream was QUEUED (backpressure), never rejected.
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

        // The recorded peak never exceeded the cap. Post-release the over-cap body only enters
        // after a prior stream has closed (freeing a slot), so it never overlaps beyond CAP — the
        // final high-water stays == CAP under the cap, but would be CALLS (=CAP+1) without it.
        assertEquals(CAP, impl.highWater(),
                () -> "final high-water " + impl.highWater() + " exceeded cap " + CAP);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
