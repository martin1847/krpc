package test.krpc.shutdown;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
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

import tech.krpc.annotation.RpcService;
import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.client.RpcClientFactory;
import tech.krpc.model.RpcResult;
import tech.krpc.server.RpcServerBuilder;
import tech.krpc.server.exe.ThreadPool;

/**
 * HARDEN-B3 fix #4 — {@link RpcServerBuilder#shutdown(Server, Duration)} performs a GRACEFUL drain:
 * new calls stop being accepted at once, but an in-flight RPC gets the grace window to finish.
 *
 * <p>Round-trip IT mirroring {@code test.krpc.stream.MaxConcurrentCallsIT}: stands up the production
 * {@code RpcServerBuilder} on a loopback port with the production virtual-thread executor and drives
 * it with a real {@code ManagedChannel}. No MySQL, no Quarkus boot.
 *
 * <p>The regression: pre-fix the exposers hand-rolled {@code server.shutdownNow()}, which ABORTS
 * in-flight RPCs — the client would see a {@code StatusRuntimeException}. The drain proof here is
 * strictly ordered: the call is confirmed running server-side, THEN graceful shutdown is initiated
 * ({@code server.isShutdown()} observed true — a condition wait, not a sleep race), and ONLY THEN is
 * the body released. A graceful drain lets that in-flight call return {@code ok("done")};
 * {@code shutdownNow()} would have cut it.
 */
class GracefulShutdownIT {

    static final String APP = "graceful-shutdown-it";

    /** Blocks server-side on a release gate so the test can hold one RPC in-flight across shutdown. */
    @UnsafeWeb
    @RpcService(description = "blocks server-side to prove graceful-shutdown drain")
    public interface DrainService {
        RpcResult<String> slow();
    }

    /** Server-side impl: signal entry, park on the release gate (bounded), then return ok("done"). */
    public static final class DrainServiceImpl implements DrainService {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public RpcResult<String> slow() {
            entered.countDown();
            try {
                // bounded await: never hang the suite even if the test forgets to release.
                release.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RpcResult.error(13, "interrupted while blocked");
            }
            return RpcResult.ok("done");
        }

        boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
            return entered.await(timeout, unit);
        }

        void release() {
            release.countDown();
        }
    }

    Server rpcServer;
    int rpcPort;
    ExecutorService serverExecutor;
    ExecutorService clientPool;
    ManagedChannel channel;
    DrainServiceImpl impl;

    @BeforeEach
    void setUp() throws Exception {
        rpcPort = freePort();
        serverExecutor = ThreadPool.newExecutor(APP, 6);
        impl = new DrainServiceImpl();
        rpcServer = new RpcServerBuilder.Builder(APP, rpcPort)
                .executor(serverExecutor)
                .addService(impl)
                .build()
                .startServer();

        channel = ManagedChannelBuilder.forAddress("127.0.0.1", rpcPort)
                .usePlaintext()
                .build();

        clientPool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        if (impl != null) impl.release();               // drain anything still parked
        if (clientPool != null) clientPool.shutdownNow();
        if (channel != null) channel.shutdownNow();
        if (rpcServer != null) rpcServer.shutdownNow();
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    void inFlightRpcDrainsOnGracefulShutdown() throws Exception {
        var svc = new RpcClientFactory(APP, channel).get(DrainService.class);

        // (1) Fire slow() and confirm the body is actually running server-side.
        Future<RpcResult<String>> callFuture = clientPool.submit(svc::slow);
        assertTrue(impl.awaitEntered(5, TimeUnit.SECONDS),
                "the RPC body must be running server-side before shutdown");

        // (2) Initiate graceful shutdown on another thread (shutdown() then awaitTermination(grace)).
        Thread shutdownThread = new Thread(
                () -> RpcServerBuilder.shutdown(rpcServer, Duration.ofSeconds(5)),
                "graceful-shutdown");
        shutdownThread.start();

        // (3) Wait until the graceful shutdown has actually begun — condition wait on isShutdown(),
        // not a timing sleep — so the release below happens with the call genuinely in-flight during
        // shutdown. shutdown() is the first thing RpcServerBuilder.shutdown does, so this trips fast.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!rpcServer.isShutdown() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(rpcServer.isShutdown(), "graceful shutdown should have been initiated");

        // (4) Release the in-flight body. A graceful drain lets it finish; shutdownNow() would cut it.
        impl.release();

        // (5) The in-flight RPC must DRAIN to a successful result — never a StatusRuntimeException.
        RpcResult<String> res;
        try {
            res = callFuture.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException ee) {
            if (ee.getCause() instanceof StatusRuntimeException sre) {
                fail("in-flight RPC was ABORTED with " + sre.getStatus()
                        + " — graceful shutdown must drain it, not shutdownNow() it");
            }
            throw ee;
        }
        assertTrue(res.isOk(),
                () -> "in-flight RPC must drain successfully, got code=" + res.getCode()
                        + " msg=" + res.getMsg());
        assertEquals("done", res.getData());

        // (6) The server terminates once the drained call completes.
        shutdownThread.join(TimeUnit.SECONDS.toMillis(10));
        assertTrue(rpcServer.isTerminated(), "server must be terminated after the graceful drain");
    }

    @Test
    void shutdownOnAlreadyTerminatedServerIsNoop() {
        // No in-flight calls: graceful shutdown terminates the idle server promptly.
        RpcServerBuilder.shutdown(rpcServer, Duration.ofSeconds(5));
        assertTrue(rpcServer.isTerminated(), "idle server should terminate under graceful shutdown");

        // A second shutdown on an already-terminated server is a no-op — must not throw.
        assertDoesNotThrow(() -> RpcServerBuilder.shutdown(rpcServer, Duration.ofSeconds(1)));
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
