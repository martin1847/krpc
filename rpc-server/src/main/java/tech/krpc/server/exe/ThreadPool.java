package tech.krpc.server.exe;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class ThreadPool {

    /**
     * Per-RPC executor backing the krpc gRPC/Netty server, replacing grpc-java's
     * default cached platform-thread pool (ServerImplBuilder.DEFAULT_EXECUTOR_POOL,
     * "grpc-default-executor").
     *
     * ADR-0002: JDK 21 + virtual threads. RPC handlers are IO-bound, so we run one
     * named virtual thread per task — no bounded pool, no queue, no rejection policy.
     */
    public static ExecutorService newExecutor(String name) {
        ThreadFactory tf = Thread.ofVirtual().name(name + "-", 0).factory();
        return Executors.newThreadPerTaskExecutor(tf);
    }

    /**
     * @deprecated the {@code base} pool-size argument is unused since ADR-0002 (VT
     * per-task has no bounded pool). Use {@link #newExecutor(String)}; this overload
     * only forwards and is kept for source compatibility with existing callers.
     */
    @Deprecated
    public static ExecutorService newExecutor(String name, int base) {
        return newExecutor(name);
    }

}