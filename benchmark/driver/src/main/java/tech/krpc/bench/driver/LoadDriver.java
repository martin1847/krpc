package tech.krpc.bench.driver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import tech.krpc.bench.contract.HelloReply;
import tech.krpc.bench.contract.HelloRequest;
import tech.krpc.bench.contract.HelloService;
import tech.krpc.client.RpcClientFactory;
import tech.krpc.model.RpcResult;

/**
 * BENCH-001 closed-loop load driver (blocking-stub). Adapted from the IOURING-001
 * Phase 2 {@code test.krpc.bench.LoadClient} so the methodology — single plaintext
 * gRPC channel (HTTP/2 multiplexed), N closed-loop worker threads, fixed warmup,
 * one measured phase — carries over and the numbers stay comparable. Two changes
 * vs the original: it drives {@code bench/Hello/hello} (the BENCH-001 unary hot
 * path) instead of {@code Demo/inc100}, and it reports p50/p99/p999 (BENCH-001 §2)
 * rather than p50/p95/p99.
 *
 * <p><b>Fixed across all four benchmarks.</b> The CLIENT transport (grpc default,
 * plaintext, one channel), the request payload, host/port, warmup and measured
 * duration are identical for every run; the only variable is the SERVER under test
 * (executor: VT vs pool; runtime: JVM vs native). This is not a unit test — a main().
 *
 * <p>Args: {@code host port threads durationSec warmupSec [label]}. Emits one
 * machine-parseable {@code RESULT ...} line the aggregator (scripts/aggregate.py)
 * consumes across repeats.
 */
public final class LoadDriver {

    /** Fixed small request payload — identical for every run. */
    private static final HelloRequest REQUEST = new HelloRequest("bench");

    public static void main(String[] args) throws Exception {
        String host = arg(args, 0, "127.0.0.1");
        int port = Integer.parseInt(arg(args, 1, "50051"));
        int threads = Integer.parseInt(arg(args, 2, "128"));
        int durationSec = Integer.parseInt(arg(args, 3, "10"));
        int warmupSec = Integer.parseInt(arg(args, 4, "5"));
        String label = arg(args, 5, "run");

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        RpcClientFactory factory = new RpcClientFactory("bench", channel);
        HelloService hello = factory.get(HelloService.class);

        Runnable call = () -> {
            RpcResult<HelloReply> r = hello.hello(REQUEST);
            HelloReply reply = r.getData();
            if (reply == null || reply.getMessage() == null) {
                throw new IllegalStateException("bad hello reply: " + r.getCode());
            }
        };

        // Warmup (not measured): let JIT / connection / pools settle. Fixed duration.
        runPhase(call, threads, warmupSec * 1000L, null, new AtomicLong());

        // Measured phase.
        List<long[]> perThread = new ArrayList<>();
        AtomicLong errors = new AtomicLong();
        long wallNanos = runPhase(call, threads, durationSec * 1000L, perThread, errors);

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
                "RESULT label=%s threads=%d durationSec=%d count=%d errors=%d rps=%.0f "
                        + "p50us=%.1f p99us=%.1f p999us=%.1f maxus=%.1f%n",
                label, threads, durationSec, total, errors.get(), rps,
                pct(all, 0.50) / 1000.0, pct(all, 0.99) / 1000.0,
                pct(all, 0.999) / 1000.0,
                (all.length == 0 ? 0 : all[all.length - 1]) / 1000.0);

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
            Thread th = new Thread(() -> {
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

    private LoadDriver() {}
}
