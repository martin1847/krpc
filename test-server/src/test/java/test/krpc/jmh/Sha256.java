/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package test.krpc.jmh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import tech.krpc.util.MD5;
import com.google.common.hash.Hashing;

/**
 *
 * @author martin
 * @version 2023/10/09 11:18
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class Sha256 {

    public static String jdk(byte[] b) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException var3) {
            throw new RuntimeException("SHA-256 is not supported." + var3.getMessage());
        }
        byte[] d = md.digest(b);

        return MD5.toLowerStrting(d);
    }

    public static String guava(byte[] data){
        return Hashing.sha256().hashBytes(data).toString();
    }


    static final byte[] bytes = "\"SHA-256 is not supported.\"".getBytes(StandardCharsets.UTF_8);


    @Benchmark
    public void jdkSha(Blackhole blackhole) {
        // 模拟方法一的逻辑
        for (int i = 0; i < 10000; i++) {
            blackhole.consume(jdk(bytes));
        }
    }

    @Benchmark
    public void methodTwo(Blackhole blackhole) {
        for (int i = 0; i < 10000; i++) {
            blackhole.consume(guava(bytes));
        }
    }

    /**
     * Benchmark          Mode  Cnt  Score   Error   Units
     * Sha256.jdkSha     thrpt    5  0.331 ± 0.005  ops/ms
     * Sha256.methodTwo  thrpt    5  0.318 ± 0.002  ops/ms
     * Sha256.jdkSha      avgt    5  2.992 ± 0.022   ms/op
     * Sha256.methodTwo   avgt    5  3.151 ± 0.034   ms/op
     *
     * Result "test.krpc.jmh.Sha256.jdkSha":
     *   2.992 ±(99.9%) 0.022 ms/op [Average]
     *   (min, avg, max) = (2.988, 2.992, 3.000), stdev = 0.006
     *   CI (99.9%): [2.971, 3.014] (assumes normal distribution)
     *
     *
     Result "test.krpc.jmh.Sha256.methodTwo":
     3.151 ±(99.9%) 0.034 ms/op [Average]
     (min, avg, max) = (3.142, 3.151, 3.164), stdev = 0.009
     CI (99.9%): [3.117, 3.185] (assumes normal distribution)
     *
     */

    public static void main(String[] args) throws Exception {
        //System.out.println(jdk(bytes));
        //System.out.println(guava(bytes));
        //org.openjdk.jmh.Main.main(args);

        var opt = new OptionsBuilder()
                .include(Sha256.class.getSimpleName())
                .forks(1)
                .build();

        new Runner(opt).run();
    }

}