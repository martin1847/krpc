package tech.krpc.http.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;

import io.netty.channel.EventLoopGroup;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/**
 * HARDEN-B4 Part A / A2 (AUD-omp-52) — bind-failure must not leak event-loop groups (case 8).
 *
 * <p>Occupies an OS-assigned port, then starts an {@link HttpServer} on the same port. {@code
 * start()} must throw AND, before rethrowing, shut down both {@code bossGroup} and {@code
 * workerGroup}. Pre-fix they leaked their NIO threads on every failed bind; the contract defended
 * here is that after the throw both groups are non-null and in a shutting-down/terminated state.
 */
class HttpServerBindLeakTest {

    @Test
    void bindFailure_shutsDownBothGroups() throws Exception {
        ServerSocket occupied = new ServerSocket(0);
        try {
            int port = occupied.getLocalPort();
            HttpServer server = new HttpServer(new DummyHandler(), port);

            // The port is actively listening, so bind(port).sync() must fail.
            assertThrows(Exception.class, server::start,
                    "start() must throw when the port is already bound");

            // AUD-omp-52: the catch block allocated both groups then shut them down before
            // rethrowing. Both must exist and be tearing down (never leaked, still running).
            EventLoopGroup boss = server.bossGroup;
            EventLoopGroup worker = server.workerGroup;
            assertNotNull(boss, "bossGroup must have been allocated");
            assertNotNull(worker, "workerGroup must have been allocated");
            assertTrue(boss.isShuttingDown() || boss.isShutdown() || boss.isTerminated(),
                    "bossGroup must be shutting down after a failed bind, not leaked");
            assertTrue(worker.isShuttingDown() || worker.isShutdown() || worker.isTerminated(),
                    "workerGroup must be shutting down after a failed bind, not leaked");
        } finally {
            occupied.close();
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
