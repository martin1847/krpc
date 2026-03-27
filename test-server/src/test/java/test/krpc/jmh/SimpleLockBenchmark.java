package test.krpc.jmh;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
public class SimpleLockBenchmark {

    // 线程本地锁 - 用于无竞争测试
    private ReentrantLock threadLocalReentrantLock;
    private final Object threadLocalMonitor = new Object();

    // 共享锁 - 通过参数注入用于有竞争测试
    @State(Scope.Benchmark)
    public static class SharedState {
        public ReentrantLock sharedReentrantLock = new ReentrantLock();
        public final Object sharedMonitor = new Object();
        public int sharedCounter;
    }

    @Setup
    public void setup() {
        threadLocalReentrantLock = new ReentrantLock();
    }

    // 无竞争测试
    @Benchmark
    @Threads(1)
    public void testReentrantLockWithoutCompetition(Blackhole blackhole) {
        threadLocalReentrantLock.lock();
        try {
            blackhole.consume(System.nanoTime());
        } finally {
            threadLocalReentrantLock.unlock();
        }
    }

    @Benchmark
    @Threads(1)
    public void testSynchronizedWithoutCompetition(Blackhole blackhole) {
        synchronized (threadLocalMonitor) {
            blackhole.consume(System.nanoTime());
        }
    }

    // 有竞争测试 - 多个线程共享同一个锁
    @Benchmark
    @Threads(4)
    public void testReentrantLockWithCompetition(SharedState state, Blackhole blackhole) {
        state.sharedReentrantLock.lock();
        try {
            state.sharedCounter++;
            blackhole.consume(state.sharedCounter);
        } finally {
            state.sharedReentrantLock.unlock();
        }
    }

    @Benchmark
    @Threads(4)
    public void testSynchronizedWithCompetition(SharedState state, Blackhole blackhole) {
        synchronized (state.sharedMonitor) {
            state.sharedCounter++;
            blackhole.consume(state.sharedCounter);
        }
    }

    public static void main(String[] args) {
        Options options = new OptionsBuilder().include(SimpleLockBenchmark.class.getSimpleName()).build();
        try {
            new Runner(options).run();
            System.out.println("==============MAIN OVER==========================");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}