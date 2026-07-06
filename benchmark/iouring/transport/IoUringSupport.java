package tech.krpc.server.iouring;

import io.grpc.ServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.incubator.channel.uring.IOUring;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.incubator.channel.uring.IOUringServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IOURING-001 Phase 2 — io_uring transport provider, discovered reflectively by
 * {@code tech.krpc.server.IoUringTransport} (rpc-server) via its fully-qualified name.
 *
 * <p>This class is the ONLY place that references the Netty io_uring incubator + grpc-netty types,
 * and it is compiled/packaged ONLY into krpc's evaluation {@code test-server} image built with
 * {@code -PwithIoUring} (gated {@code src/iouring} source set). rpc-server itself has no io_uring
 * dependency; a production deployment gets the byte-identical default transport unless it opts in
 * by supplying this provider + the incubator artifacts. Located by name only, so in a native image
 * built WITHOUT {@code -PwithIoUring} the class is absent and unreachable → zero io_uring footprint.
 *
 * <p>grpc 1.79 has no built-in io_uring selection, so channelType + boss/worker EventLoopGroup are
 * wired explicitly (grpc's all-or-none invariant). Uses the Netty 4.1-line incubator; the graduated
 * {@code io.netty.channel.uring} is Netty-4.2-only and we are Netty-4.1-pinned.
 */
public final class IoUringSupport {

    private static final Logger log = LoggerFactory.getLogger(IoUringSupport.class);

    private IoUringSupport() {}

    /**
     * Reflective entry point (see {@code IoUringTransport}).
     *
     * @return a NettyServerBuilder wired to io_uring, or {@code null} if io_uring is unavailable at
     *         runtime (non-Linux, kernel &lt; 5.1, seccomp block, missing .so) → caller falls back.
     */
    public static ServerBuilder<?> newServerBuilder(int port) {
        if (!IOUring.isAvailable()) {
            log.warn("[io_uring] KRPC_IOURING set but IOUring.isAvailable()=false ({}); "
                    + "falling back to default (NIO) transport",
                    String.valueOf(IOUring.unavailabilityCause()));
            return null;
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
