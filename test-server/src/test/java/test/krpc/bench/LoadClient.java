package test.krpc.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import tech.krpc.client.RpcClientFactory;
import tech.test.krpc.DemoService;

/**
 * IOURING-001 Phase 2 benchmark load driver (closed-loop, blocking-stub).
 *
 * <p>Drives {@code Demo/inc100} (unary hot path) or {@code Demo/bytesTime} (bytes path) against a
 * krpc server over a single plaintext gRPC channel (HTTP/2 multiplexed) with N concurrent threads.
 * Reports p50/p95/p99 latency (µs), RPS, and error count. The client transport is fixed (grpc
 * default) across all runs, so the only variable between runs is the SERVER transport (NIO vs
 * io_uring), toggled server-side by KRPC_IOURING.
 *
 * <p>Args: {@code host port method threads durationSec warmupSec}. Not a unit test — a main().
 */
public final class LoadClient {

    public static void main(String[] args) throws Exception {
        String host = arg(args, 0, "127.0.0.1");
        int port = Integer.parseInt(arg(args, 1, "50051"));
        String method = arg(args, 2, "inc100");
        int threads = Integer.parseInt(arg(args, 3, "16"));
        int durationSec = Integer.parseInt(arg(args, 4, "10"));
        int warmupSec = Integer.parseInt(arg(args, 5, "3"));

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        RpcClientFactory factory = new RpcClientFactory("test-server", channel);
        DemoService demo = factory.get(DemoService.class);

        Runnable call = callFor(method, demo);

        // Warmup (not measured): drive traffic so JIT/connection/pools settle.
        runPhase(call, threads, warmupSec * 1000L, null, new AtomicLong());

        // Measured phase.
        List<long[]> perThread = new ArrayList<>();
        AtomicLong errors = new AtomicLong();
        long wallNanos = runPhase(call, threads, durationSec * 1000L, perThread, errors);

        // Merge + percentiles.
        long total = 0;
        for (long[] a : perThread) total += a.length;
        long[] all = new long[(int) total];
        int idx = 0;
        for (long[] a : perThread) {
            System.arraycopy(a, 0, all, idx, a.length);
            idx += a.length;
        }
        java.util.Arrays.sort(all);

        double rps = total / (wallNanos / 1_000_000_000.0);
        System.out.printf(
                "RESULT method=%s threads=%d durationSec=%d count=%d errors=%d rps=%.0f "
                        + "p50us=%.1f p95us=%.1f p99us=%.1f maxus=%.1f%n",
                method, threads, durationSec, total, errors.get(), rps,
                pct(all, 0.50) / 1000.0, pct(all, 0.95) / 1000.0,
                pct(all, 0.99) / 1000.0, (all.length == 0 ? 0 : all[all.length - 1]) / 1000.0);

        factory.close();
        channel.shutdownNow();
    }

    /** Runs {@code threads} closed-loop workers for {@code millis}; returns wall nanos of the phase. */
    private static long runPhase(Runnable call, int threads, long millis,
                                 List<long[]> perThreadOut, AtomicLong errors) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        List<long[]> collected = new ArrayList<>();
        long deadlineOffset = millis;
        for (int t = 0; t < threads; t++) {
            long[] holder = new long[0];
            Thread th = new Thread(() -> {
                // grow-able latency buffer per thread
                long[] buf = new long[4096];
                int n = 0;
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { return; }
                long end = System.nanoTime() + deadlineOffset * 1_000_000L;
                while (System.nanoTime() < end) {
                    long s = System.nanoTime();
                    try {
                        call.run();
                    } catch (Throwable ex) {
                        errors.incrementAndGet();
                        continue;
                    }
                    long d = System.nanoTime() - s;
                    if (perThreadOut != null) {
                        if (n == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                        buf[n++] = d;
                    }
                }
                if (perThreadOut != null) {
                    synchronized (collected) { collected.add(java.util.Arrays.copyOf(buf, n)); }
                }
            });
            th.setName("load-" + t);
            ts.add(th);
            th.start();
        }
        ready.await();
        long start = System.nanoTime();
        go.countDown();
        for (Thread th : ts) th.join();
        long wall = System.nanoTime() - start;
        if (perThreadOut != null) perThreadOut.addAll(collected);
        return wall;
    }

    private static Runnable callFor(String method, DemoService demo) {
        switch (method) {
            case "inc100":
                return () -> {
                    Integer r = demo.inc100(1).getData();
                    if (r == null || r != 101) throw new IllegalStateException("bad inc100=" + r);
                };
            case "bytesTime":
                return () -> {
                    byte[] r = demo.bytesTime().getData();
                    if (r == null || r.length != 6) throw new IllegalStateException("bad bytesTime len");
                };
            default:
                throw new IllegalArgumentException("unknown method: " + method);
        }
    }

    private static long pct(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.ceil(p * sorted.length) - 1;
        if (i < 0) i = 0;
        if (i >= sorted.length) i = sorted.length - 1;
        return sorted[i];
    }

    private static String arg(String[] a, int i, String def) {
        return (a != null && a.length > i && a[i] != null && !a[i].isBlank()) ? a[i] : def;
    }

    private LoadClient() {}
}
