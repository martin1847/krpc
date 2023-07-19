/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package test.btyx.jmh;

import java.util.concurrent.TimeUnit;

import com.bt.rpc.client.RpcClientFactory;
import com.btyx.demo.service.DemoService;
import io.grpc.ManagedChannelBuilder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 *
 *
 * direct 在纯计算、异步场景下占优，省去了一次上下文切换和一些同步操作，直接用链接线程去处理业务。
 * 大多数耗时涉及io场景默认策略更优。耗时长场景下下降厉害。
 * https://groups.google.com/g/grpc-io/c/Zy87GhcwazM
 *
 # Run complete. Total time: 00:09:30


 Benchmark                                 Mode   Cnt    Score   Error   Units
 JmhRpcClient.direct1                     thrpt     2    0.380          ops/ms
 JmhRpcClient.direct10                    thrpt     2    0.227          ops/ms
 JmhRpcClient.direct50                    thrpt     2    0.112          ops/ms
 JmhRpcClient.normal1                     thrpt     2    0.388          ops/ms
 JmhRpcClient.normal10                    thrpt     2    0.282          ops/ms
 JmhRpcClient.normal50                    thrpt     2    0.117          ops/ms
 JmhRpcClient.direct1                    sample  8659   18.463 ± 0.390   ms/op
 JmhRpcClient.direct1:direct1·p0.00      sample          5.571           ms/op
 JmhRpcClient.direct1:direct1·p0.50      sample         16.777           ms/op
 JmhRpcClient.direct1:direct1·p0.90      sample         27.001           ms/op
 JmhRpcClient.direct1:direct1·p0.95      sample         30.900           ms/op
 JmhRpcClient.direct1:direct1·p0.99      sample         39.872           ms/op
 JmhRpcClient.direct1:direct1·p0.999     sample        180.156           ms/op
 JmhRpcClient.direct1:direct1·p0.9999    sample        299.893           ms/op
 JmhRpcClient.direct1:direct1·p1.00      sample        299.893           ms/op
 JmhRpcClient.direct10                   sample  6178   25.875 ± 0.244   ms/op
 JmhRpcClient.direct10:direct10·p0.00    sample         14.238           ms/op
 JmhRpcClient.direct10:direct10·p0.50    sample         25.149           ms/op
 JmhRpcClient.direct10:direct10·p0.90    sample         33.128           ms/op
 JmhRpcClient.direct10:direct10·p0.95    sample         35.983           ms/op
 JmhRpcClient.direct10:direct10·p0.99    sample         43.071           ms/op
 JmhRpcClient.direct10:direct10·p0.999   sample         65.274           ms/op
 JmhRpcClient.direct10:direct10·p0.9999  sample         74.056           ms/op
 JmhRpcClient.direct10:direct10·p1.00    sample         74.056           ms/op
 JmhRpcClient.direct50                   sample  1471  108.555 ± 6.285   ms/op
 JmhRpcClient.direct50:direct50·p0.00    sample         54.591           ms/op
 JmhRpcClient.direct50:direct50·p0.50    sample         77.988           ms/op
 JmhRpcClient.direct50:direct50·p0.90    sample        240.124           ms/op
 JmhRpcClient.direct50:direct50·p0.95    sample        292.553           ms/op
 JmhRpcClient.direct50:direct50·p0.99    sample        363.856           ms/op
 JmhRpcClient.direct50:direct50·p0.999   sample        398.983           ms/op
 JmhRpcClient.direct50:direct50·p0.9999  sample        398.983           ms/op
 JmhRpcClient.direct50:direct50·p1.00    sample        398.983           ms/op
 JmhRpcClient.normal1                    sample  9023   17.713 ± 0.247   ms/op
 JmhRpcClient.normal1:normal1·p0.00      sample          5.489           ms/op
 JmhRpcClient.normal1:normal1·p0.50      sample         16.548           ms/op
 JmhRpcClient.normal1:normal1·p0.90      sample         26.444           ms/op
 JmhRpcClient.normal1:normal1·p0.95      sample         29.917           ms/op
 JmhRpcClient.normal1:normal1·p0.99      sample         38.666           ms/op
 JmhRpcClient.normal1:normal1·p0.999     sample         74.577           ms/op
 JmhRpcClient.normal1:normal1·p0.9999    sample        119.276           ms/op
 JmhRpcClient.normal1:normal1·p1.00      sample        119.276           ms/op
 JmhRpcClient.normal10                   sample  5611   28.469 ± 0.369   ms/op
 JmhRpcClient.normal10:normal10·p0.00    sample         14.647           ms/op
 JmhRpcClient.normal10:normal10·p0.50    sample         26.673           ms/op
 JmhRpcClient.normal10:normal10·p0.90    sample         38.732           ms/op
 JmhRpcClient.normal10:normal10·p0.95    sample         45.679           ms/op
 JmhRpcClient.normal10:normal10·p0.99    sample         59.344           ms/op
 JmhRpcClient.normal10:normal10·p0.999   sample         70.669           ms/op
 JmhRpcClient.normal10:normal10·p0.9999  sample         79.823           ms/op
 JmhRpcClient.normal10:normal10·p1.00    sample         79.823           ms/op
 JmhRpcClient.normal50                   sample  2392   66.709 ± 0.402   ms/op
 JmhRpcClient.normal50:normal50·p0.00    sample         54.591           ms/op
 JmhRpcClient.normal50:normal50·p0.50    sample         66.126           ms/op
 JmhRpcClient.normal50:normal50·p0.90    sample         74.449           ms/op
 JmhRpcClient.normal50:normal50·p0.95    sample         78.427           ms/op
 JmhRpcClient.normal50:normal50·p0.99    sample         82.640           ms/op
 JmhRpcClient.normal50:normal50·p0.999   sample         94.361           ms/op
 JmhRpcClient.normal50:normal50·p0.9999  sample         98.173           ms/op
 JmhRpcClient.normal50:normal50·p1.00    sample         98.173           ms/op


 * @author Martin.C
 * @version 2022/11/10 17:47
 */
//@BenchmarkMode({Mode.All}), Mode.AverageTime
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Measurement(iterations = 2, time = 10)
@Warmup(iterations = 1, time = 10)
@Threads(value = 8)
@State(Scope.Benchmark)
//@Fork(1)
public class JmhRpcClient {


    DemoService direct, normal;


    static DemoService makeService(boolean directExecutor){
        var builder =
                ManagedChannelBuilder.forAddress("example.testbtyxapi.com",443)
                        .useTransportSecurity();
        if(directExecutor){
            builder.directExecutor();
        }
        var fac = new RpcClientFactory("demo-java-server",builder.build());

        return fac.get(DemoService.class);
    }

    public JmhRpcClient() {

        direct =  makeService(true);
        normal =  makeService(false);
    }


    @Benchmark
    public Integer direct1() {
        return direct.sleep(1).getCode();
    }


    @Benchmark
    public Integer direct10() {
        return direct.sleep(10).getCode();
    }

    @Benchmark
    public Integer direct50() {
        return direct.sleep(50).getCode();
    }


    @Benchmark
    public Integer normal1() {
        return normal.sleep(1).getCode();
    }

    @Benchmark
    public Integer normal10() {
        return normal.sleep(10).getCode();
    }

    @Benchmark
    public Integer normal50() {
        return normal.sleep(50).getCode();
    }

    public static void main(String[] args) throws RunnerException {
        var opt = new OptionsBuilder()
                .include(JmhRpcClient.class.getSimpleName())
                .forks(1)
                .build();

        new Runner(opt).run();
    }
}