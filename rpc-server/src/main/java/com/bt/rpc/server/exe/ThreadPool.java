package com.bt.rpc.server.exe;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class ThreadPool {

    // cached thread Pool
    //  private static final String NAME = "grpc-default-executor";
    //

    /**
     * https://github.com/LesnyRumcajs/grpc_bench
     *
     * https://github.com/grpc/grpc-java/issues/7381#issuecomment-685894912
     *
     * .如何估算线程数？
     * 在电商业务类型下，任务大部分都是IO密集型的，而IO密集型的线程数可以根据如下公式计算得出：线程池线程数=CPU核数*
     * (响应时间/（响应时间-调用第三方接口时间-访问数据库时间））。
     *
     * 比如一个获取商品详细的接口平均响应时间为50ms，调用库存接口用了10ms，调用优惠接口用了10ms，调用数据库用来20ms，
     * 该服务所在机器CPU核数为10，则可以估算出线程数为:threads=10*(50/50-10-10-20)=50。
     */
    public static ExecutorService newExecutor(String name, int base) {
        int cpus = Math.max(base, Runtime.getRuntime().availableProcessors());
        var tf = new ThreadFactoryBuilder()
                .setNameFormat(name + "-%d")
                .setDaemon(true)
                .build();
        return new ThreadPoolExecutor(cpus * 2, cpus * 6, 60, TimeUnit.SECONDS,
                 new LinkedBlockingQueue<>(cpus * 20 ), tf, new AbortPolicyWithReport(name));
    }

}