package test.krpc.jmh;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@BenchmarkMode(Mode.AverageTime)
@Measurement(iterations = 3)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@State(Scope.Benchmark) // 测试多线程性能
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class LockBenchmark {
    private static Object        lock          = new Object();
    private static ReentrantLock reentrantLock = new ReentrantLock();
    private static long          cnt           = 0;
    private static long          frequency     = 10000;

    @Benchmark
    @Threads(1) // 指定一个线程，重入 10000 次
    public void testReentrantLockWithoutCompetition() {
        for (int i = 0; i < frequency; i++) {
            doSomethingWithReentrantLock();
        }
    }

    @Benchmark
    @Threads(1) // 指定一个线程，重入 10000 次
    public void testSynchronizedWithoutCompetition() {
        for (int i = 0; i < frequency; i++) {
            doSomethingWithSynchronized();
        }
    }

    @Benchmark
    @Threads(10) // 指定十个线程
    public void testReentrantLockWithCompetition() {
        for (int i = 0; i < (frequency / 1000); i++) {
            doSomethingWithReentrantLock();
        }
    }

    @Benchmark
    @Threads(10) // 指定十个线程
    public void testSynchronizedWithCompetition() {
        for (int i = 0; i < (frequency / 1000); i++) {
            doSomethingWithSynchronized();
        }
    }

    private void doSomethingWithReentrantLock() {
        reentrantLock.lock();
        cnt += 1;
        if (cnt >= (Long.MAX_VALUE >> 1)) {
            cnt = 0;
        }
        reentrantLock.unlock();
    }

    private synchronized void doSomethingWithSynchronized() {
        cnt += 1;
        if (cnt >= (Long.MAX_VALUE >> 1)) {
            cnt = 0;
        }
    }

    public static void main(String[] args) {
        Options options = new OptionsBuilder().include(LockBenchmark.class.getSimpleName()).build();
        try {
            new Runner(options).run();
            System.out.println("==============MAIN OVER==========================");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}