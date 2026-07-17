package tech.krpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;

import tech.krpc.annotation.RpcService;
import tech.krpc.client.RpcClientFactory;
import tech.krpc.model.RpcResult;

/**
 * BIND-HOST: {@code RpcServerBuilder} listen-address selection.
 *
 * <p>Set (system property {@code rpc.server.bindHost} / env {@code KRPC_BIND_HOST}) → the gRPC face
 * binds exactly that address (proven both on the started {@link Server}'s bound socket and by a real
 * loopback client call). Unset → the previous wildcard bind is preserved byte-for-byte (NS-6).
 */
class RpcServerBindHostTest {

    static final String APP = "bind-host-it";

    @RpcService("Echo")
    public interface EchoService {
        RpcResult<String> echo(String s);
    }

    static class EchoServiceImpl implements EchoService {
        @Override
        public RpcResult<String> echo(String s) {
            return RpcResult.ok(s);
        }
    }

    private Server server;
    private ManagedChannel channel;
    private ExecutorService exec;

    @AfterEach
    void tearDown() {
        // BIND-HOST is process-wide: never leak the property to a sibling test.
        System.clearProperty("rpc.server.bindHost");
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
        if (exec != null) exec.shutdownNow();
    }

    @Test
    void bindHostSet_bindsAndServesOnLoopback() throws Exception {
        System.setProperty("rpc.server.bindHost", "127.0.0.1");
        int port = freePort();
        exec = Executors.newVirtualThreadPerTaskExecutor();
        server = new RpcServerBuilder.Builder(APP, port).executor(exec)
                .addService(new EchoServiceImpl()).build().startServer();

        // Honest, non-flaky: the started server's bound socket is the loopback address, not wildcard.
        assertEquals(1, server.getListenSockets().size(), "single listen socket expected");
        InetSocketAddress bound = (InetSocketAddress) server.getListenSockets().get(0);
        assertEquals("127.0.0.1", bound.getAddress().getHostAddress(),
                "gRPC face must bind the configured host");
        assertFalse(bound.getAddress().isAnyLocalAddress(),
                "must NOT be the wildcard address when bindHost is set");

        // Connect via 127.0.0.1 → succeeds.
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
        EchoService client = new RpcClientFactory(APP, channel).get(EchoService.class);
        RpcResult<String> r = client.echo("hi");
        assertTrue(r.isOk(), () -> "loopback call must succeed, got " + r.getCode() + " " + r.getMsg());
        assertEquals("hi", r.getData());
    }

    @Test
    void bindHostUnset_keepsWildcardBind() throws Exception {
        // Explicitly unset (env KRPC_BIND_HOST is absent in the test JVM).
        System.clearProperty("rpc.server.bindHost");
        int port = freePort();
        exec = Executors.newVirtualThreadPerTaskExecutor();
        server = new RpcServerBuilder.Builder(APP, port).executor(exec)
                .addService(new EchoServiceImpl()).build().startServer();

        InetSocketAddress bound = (InetSocketAddress) server.getListenSockets().get(0);
        assertTrue(bound.getAddress().isAnyLocalAddress(),
                () -> "unset bindHost must keep the wildcard bind, got " + bound);
    }

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
