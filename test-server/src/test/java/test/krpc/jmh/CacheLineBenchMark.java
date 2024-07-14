package test.krpc.jmh;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
//--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED
import jdk.internal.vm.annotation.Contended;

//File menu -> [Settings] -> [Build, Execution, Deployment] -> [Java Compiler]
// and check the setting "Project bytecode version"
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 1, jvmArgsPrepend = "-XX:-RestrictContended")
@Warmup(iterations = 10)
@Measurement(iterations = 25)
@Threads(2)
public class CacheLineBenchMark {

    // show object layout
    //12字节的对象头+8字节的long+8字节的long+4字节的补全，总共应该是32字节。
    //因为32字节< 64字节，所以一个cache line就可以将其包括。
    static class CacheLine{
        public  long a;
        public  long b;
    }


    static class CacheLinePadded{


        public  long a;

        @Contended
        public  long b;
    }

    private CacheLine cacheLine= new CacheLine();
    private CacheLinePadded cacheLinePadded = new CacheLinePadded();

    @Group("unpadded")
    @GroupThreads(1)
    @Benchmark
    public long updateUnpaddedA() {
        return cacheLine.a++;
    }

    @Group("unpadded")
    @GroupThreads(1)
    @Benchmark
    public long updateUnpaddedB() {
        return cacheLine.b++;
    }

    @Group("padded")
    @GroupThreads(1)
    @Benchmark
    public long updatePaddedA() {
        return cacheLinePadded.a++;
    }

    @Group("padded")
    @GroupThreads(1)
    @Benchmark
    public long updatePaddedB() {
        return cacheLinePadded.b++;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(CacheLineBenchMark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
