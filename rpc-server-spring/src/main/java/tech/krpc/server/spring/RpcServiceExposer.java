/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package tech.krpc.server.spring;

import java.util.ArrayList;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;

import io.grpc.Server;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.Validator;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.SmartLifecycle;
import tech.krpc.annotation.RpcService;
import tech.krpc.common.RpcConstants;
import tech.krpc.filter.GlobalFilter;
import tech.krpc.filter.GlobalFilter.Order;
import tech.krpc.server.Filters;
import tech.krpc.server.RpcServerBuilder;
import tech.krpc.server.ServerContext;
import tech.krpc.server.ServerFilter;
import tech.krpc.server.exe.ThreadPool;
import tech.krpc.util.EnvUtils;

/**
 *
 * @author martin
 * @version 2025/12/05 22:29
 */
@ConditionalOnProperty(name = "rpc.server.app")
@Slf4j
@ConfigurationProperties(prefix = "rpc.server")
@Named
public class RpcServiceExposer implements ApplicationContextAware, SmartLifecycle {

    @Setter
    String app;

    @Setter
    int port = RpcConstants.DEFAULT_PORT;

    @Setter
    boolean defaultExecutor = false;

    static {
        //log.debug("static GraalvmBuild.forNative.....");
        //io.grpc.ManagedChannelProvider$ProviderNotFoundException: No functional server found. Try adding a dependency on the grpc-netty
        // or grpc-netty-shaded artifact quarkus graal native
        //https://github.com/quarkusio/quarkus/blob/main/extensions/grpc/runtime/src/main/java/io/quarkus/grpc/spi/GrpcBuilderProvider.java
        if (!RpcConstants.CI_BUILD_ID.startsWith("null")) {
            System.out.println("[ RpcServiceExpose ] CI_BUILD_ID :" + RpcConstants.CI_BUILD_ID);
        }
        //see Target_io_netty_util_internal_logging_InternalLoggerFactory
    }

    Server server;

    ExecutorService executor;

    @Inject
    Validator     validator;
    @Inject
    InitJwsVerify initJwsVerify;

    ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    int startServer(ApplicationContext context) throws Exception {
        var proxyServerBuilder = new RpcServerBuilder.Builder(app, port);

        proxyServerBuilder.executor(executor);

        var beans = context.getBeansWithAnnotation(RpcService.class);
        int i = 0;
        for (var kv : beans.entrySet()) {
            var beanName = kv.getKey();
            var filterList = new ArrayList<ServerFilter>();
            var fa = context.findAnnotationOnBean(beanName, Filters.class);
            if (null != fa) {
                for (var f : fa.value()) {
                    var flt = applicationContext.getBean(f);
                    filterList.add(flt);
                }
            }
            proxyServerBuilder.addService(kv.getValue(), filterList);
            i++;
            if (filterList.size() > 0) {
                log.info("Found RpcService :=> {}  has Filters {} ", beanName, filterList);
            }
        }
        server = proxyServerBuilder.build().startServer();
        return i;
    }

    public void initValidator() {
        //var validators = CDI.current().select(Validator.class);
        //if (validators.isResolvable()) {
        if (validator instanceof EmptyValidator) {
            log.warn("EmptyValidator Found, All Rpc Validator will Skip, Are you Sure?");
        } else {
            log.info("[ Reg GlobalValidator ] :  {}", validator);
            ServerContext.regValidator(validator);
        }
    }

    public void initFilter(ApplicationContext applicationContext) {
        var beans = applicationContext.getBeansWithAnnotation(GlobalFilter.class);
        var map = new TreeMap<Order, ServerFilter>();
        beans.forEach((bean, it) -> {
            var gf = applicationContext.findAnnotationOnBean(bean, GlobalFilter.class);
            if (null != gf) {
                map.put(new Order(gf.value(), it.getClass().getSimpleName()),
                        (ServerFilter) it);
            }
        });
        map.forEach((k, v) -> {
            log.info("[ Reg GlobalFilter ] :  {}", k);
            ServerContext.regGlobalFilter(v);
        });
    }

    @Override
    public void start() {
        log.debug("*******************rpc.enable RpcServerAutoConfigure*******************************");
        log.debug(" app = {} , port = {}", app, port);
        log.debug(" validator = {}", validator);

        initFilter(applicationContext);

        initValidator();

        initJwsVerify.init();

        if (!defaultExecutor) {
            // instead of ServerImplBuilder.DEFAULT_EXECUTOR_POOL ( SHARED_CHANNEL_EXECUTOR/ NAME = "grpc-default-executor")
            var name = app + "-rpc";
            var cpus = Runtime.getRuntime().availableProcessors();
            if (cpus < 6) {
                // docker may be 1
                log.info(" cpus is too small {} , change to default 6.", cpus);
                cpus = 6;
            }
            executor = ThreadPool.newExecutor(name, cpus);
            log.info("Init Executor {}({} cpus),  instead of ServerImplBuilder.DEFAULT_EXECUTOR_POOL", name, cpus);
        } else {
            log.info("Use CachedThreadPool ServerImplBuilder.DEFAULT_EXECUTOR_POOL grpc-default-executor.");
        }

        try {
            int serviceSize = startServer(applicationContext);
            log.info("***** 【 {} 】 RpcServer expose {} services on {}, {}.", EnvUtils.current(), serviceSize, port,
                    RpcConstants.CI_BUILD_ID);
        } catch (Exception e) {
            log.error("start krpc netty server failed !!!", e);
            throw new RuntimeException(e);
        }
        //
        try {
            server.awaitTermination();
            //Thread.currentThread().join();
        } catch (InterruptedException e) {
            log.warn("server may be killed, prepare shutdown now...", e);
        }
    }

    @Override
    public void stop() {

        var waitTask = 0;
        if (null != server) {
            server.shutdownNow();
        }
        if (null != executor) {
            waitTask = executor.shutdownNow().size();
        }
        server = null;
        log.info("***** 【 {} 】 RpcServer shutting down with {} waiting tasks, since JVM is shutting down.", EnvUtils.current(), waitTask);
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    //
    //@Bean
    //public InitJwsVerify initJwsVerify()  {
    //    log.debug("*******************【 USE InitJwsVerify 】******************************* ");
    //    return new InitJwsVerify();
    //}
}