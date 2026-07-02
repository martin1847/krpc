package tech.krpc.server;

import io.grpc.ServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.incubator.channel.uring.IOUring;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.incubator.channel.uring.IOUringServerSocketChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * IOURING-001 Phase 2 — flag-gated io_uring transport selection for the gRPC server.
 *
 * <p>Default <b>OFF</b> = byte-identical behavior: returns the generic
 * {@link ServerBuilder#forPort(int)} exactly as before (grpc picks {@code NettyServerProvider}
 * via ServiceLoader → NIO in native, where epoll is passively disabled).
 *
 * <p><b>ON</b> (env {@code KRPC_IOURING=1|true|on} or {@code -Dkrpc.transport.iouring=true}):
 * wires {@link NettyServerBuilder} to the Netty 4.1-line io_uring <em>incubator</em> transport
 * ({@code io.netty.incubator.channel.uring}); the graduated {@code io.netty.channel.uring} is
 * Netty-4.2-only and we are pinned to Netty 4.1 (grpc-netty 1.79 + Quarkus 3.33 LTS). grpc 1.79
 * has no built-in io_uring selection, so channelType + boss/worker EventLoopGroup must be wired
 * explicitly (grpc's all-or-none invariant). Guarded by {@link IOUring#isAvailable()} — if
 * io_uring is unavailable (non-Linux host, kernel &lt; 5.1, seccomp block, missing .so) it falls
 * back to the default transport rather than failing to boot.
 *
 * <p>All io_uring class references are isolated in this class so the OFF path never loads them.
 */
@Slf4j
final class IoUringTransport {

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
     * Server builder for {@code port}: io_uring when the flag is ON and io_uring is available,
     * otherwise the unchanged default. The OFF path is byte-identical to the prior behavior.
     */
    static ServerBuilder<?> newServerBuilder(int port) {
        if (!flagEnabled()) {
            return ServerBuilder.forPort(port);
        }
        if (!IOUring.isAvailable()) {
            log.warn("[io_uring] KRPC_IOURING set but IOUring.isAvailable()=false ({}); "
                    + "falling back to default (NIO) transport",
                    String.valueOf(IOUring.unavailabilityCause()));
            return ServerBuilder.forPort(port);
        }
        EventLoopGroup boss = new IOUringEventLoopGroup(1);
        EventLoopGroup worker = new IOUringEventLoopGroup();
        log.info("[io_uring] ENABLED: NettyServerBuilder + IOUringServerSocketChannel (boss=1, worker=default)");
        return NettyServerBuilder.forPort(port)
                .channelType(IOUringServerSocketChannel.class)
                .bossEventLoopGroup(boss)
                .workerEventLoopGroup(worker);
    }
}
