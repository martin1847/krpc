package tech.krpc.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * BIND-HOST: the krpc-http netty face ({@link HttpServer}) honours the same listen-address config as
 * the gRPC face. Set → binds exactly that address (asserted on the retained server channel and by a
 * real loopback TCP connect). Unset → the previous wildcard {@code bind(port)} is preserved (NS-6).
 */
class HttpServerBindHostTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        System.clearProperty("rpc.server.bindHost");
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void bindHostSet_bindsLoopbackAndAcceptsConnection() throws Exception {
        System.setProperty("rpc.server.bindHost", "127.0.0.1");
        int port = freePort();
        server = new HttpServer(new DummyHandler(), port);
        server.start();

        assertNotNull(server.serverChannel, "server channel must be retained after start()");
        InetSocketAddress bound = (InetSocketAddress) server.serverChannel.localAddress();
        assertEquals("127.0.0.1", bound.getAddress().getHostAddress(),
                "http face must bind the configured host");
        assertFalse(bound.getAddress().isAnyLocalAddress(),
                "must NOT be the wildcard address when bindHost is set");

        // Connect via 127.0.0.1 → succeeds.
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            assertTrue(s.isConnected(), "loopback connect to the bound port must succeed");
        }
    }

    @Test
    void bindHostUnset_keepsWildcardBind() throws Exception {
        System.clearProperty("rpc.server.bindHost");
        int port = freePort();
        server = new HttpServer(new DummyHandler(), port);
        server.start();

        InetSocketAddress bound = (InetSocketAddress) server.serverChannel.localAddress();
        assertTrue(bound.getAddress().isAnyLocalAddress(),
                () -> "unset bindHost must keep the wildcard bind, got " + bound);
    }

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    /** Minimal concrete handler; never actually serves a request in this test. */
    static final class DummyHandler extends AbstractHttpHandler {
        @Override
        public Validator getValidator() {
            return null;
        }

        @Override
        public void initHandler() {
            // no handlers registered
        }
    }
}
