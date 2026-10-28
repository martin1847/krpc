package tech.krpc.server;

import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;

/**
 * IOURING-001 Phase 2 — flag-gated io_uring transport selection for the gRPC server.
 *
 * <p>Default <b>OFF</b> = byte-identical behavior: returns the generic
 * {@link ServerBuilder#forPort(int)} exactly as before (grpc picks {@code NettyServerProvider}
 * via ServiceLoader → NIO in native, where epoll is passively disabled).
 *
 * <p><b>ON</b> (env {@code KRPC_IOURING=1|true|on} or {@code -Dkrpc.transport.iouring=true}):
 * reflectively delegates to an {@code IoUringSupport} provider that wires the server to the Netty
 * 4.1-line io_uring incubator transport.
 *
 * <p><b>This library class references NO io_uring / grpc-netty type and does not depend on them.</b>
 * The actual wiring lives in a separate {@code IoUringSupport} provider that a deployment supplies
 * (e.g. krpc's {@code test-server} when built with {@code -PwithIoUring}). It is located by name via
 * {@link Class#forName}, so:
 * <ul>
 *   <li>a deployment that does not bundle io_uring has no such class → we fall back to the default
 *       transport (no {@code NoClassDefFoundError});</li>
 *   <li>in a GraalVM native image without io_uring bundled, {@code IoUringSupport} is
 *       <em>unreachable</em> (only a string-literal {@code Class.forName}, no reflection metadata),
 *       so its io_uring references are never analyzed and the default image is byte-identical to a
 *       build with no io_uring at all.</li>
 * </ul>
 */
@Slf4j
final class IoUringTransport {

    /** Provider class supplied by the io_uring-enabled deployment; located by name only. */
    private static final String SUPPORT_CLASS = "tech.krpc.server.iouring.IoUringSupport";

    private IoUringTransport() {}

    // Read the flag at RUNTIME, never a static-final field: Quarkus initializes app classes at
    // build time by default, which would constant-fold a static flag to the build-time env value
    // (false) and ignore the runtime KRPC_IOURING. newServerBuilder() is called once at boot, so
    // a live read is cheap and correct on both JVM and native.
    private static boolean flagEnabled() {
        String v = System.getProperty("krpc.transport.iouring");
        if (v == null || v.isBlank()) {
            v = System.getenv("KRPC_IOURING");
        }
        if (v == null) {
            return false;
        }
        v = v.trim();
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on");
    }

    /** Whether the io_uring flag is set (runtime read; regardless of io_uring availability). */
    static boolean flag() {
        return flagEnabled();
    }

    /**
     * Server builder for {@code port}: io_uring when the flag is ON and an {@code IoUringSupport}
     * provider is present and reports io_uring available; otherwise the unchanged default. The OFF
     * path is byte-identical to the prior behavior.
     */
    static ServerBuilder<?> newServerBuilder(int port) {
        if (!flagEnabled()) {
            return ServerBuilder.forPort(port);
        }
        try {
            ClassLoader cl = IoUringTransport.class.getClassLoader();
            Class<?> support = Class.forName(SUPPORT_CLASS, true, cl);
            Object b = support.getMethod("newServerBuilder", int.class).invoke(null, port);
            if (b instanceof ServerBuilder<?> sb) {
                return sb;   // io_uring wired
            }
            // provider returned null => io_uring unavailable at runtime; it logged the cause.
            return ServerBuilder.forPort(port);
        } catch (ClassNotFoundException e) {
            log.warn("[io_uring] KRPC_IOURING set but no io_uring provider ({}) on the classpath; "
                    + "falling back to default (NIO) transport", SUPPORT_CLASS);
            return ServerBuilder.forPort(port);
        } catch (Throwable t) {   // LinkageError / UnsatisfiedLinkError / reflection / init failure
            log.warn("[io_uring] KRPC_IOURING set but io_uring init failed ({}); "
                    + "falling back to default (NIO) transport", t.toString());
            return ServerBuilder.forPort(port);
        }
    }
}
