/**
 * krpc.tech
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package tech.krpc.server.spring;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;

import io.grpc.Server;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.Validator;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
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
import tech.krpc.context.KrpcOtel;
import io.opentelemetry.api.OpenTelemetry;

/**
 *
 * @author martin
 * @version 2025/12/05 22:29
 */
@ConditionalOnProperty(name = "rpc.server.app")
@Slf4j
@ConfigurationProperties(prefix = "rpc.server")
@Named
public class RpcServiceExposer implements ApplicationListener<ApplicationReadyEvent> {

    @Setter
    String app;

    @Setter
    int port = RpcConstants.DEFAULT_PORT;

    @Setter
    boolean defaultExecutor = false;

    // D2 (2026-07-03): CVE-2026-47244 app-layer cap. @ConfigurationProperties relaxed
    // binding maps rpc.server.max-concurrent-calls-per-connection + env. 0 = unlimited.
    @Setter
    int maxConcurrentCallsPerConnection = RpcConstants.DEFAULT_MAX_CONCURRENT_CALLS_PER_CONNECTION;

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

    int startServer(ApplicationContext context) throws Exception {
        var proxyServerBuilder = new RpcServerBuilder.Builder(app, port);

        proxyServerBuilder.executor(executor);
        proxyServerBuilder.maxConcurrentCallsPerConnection(maxConcurrentCallsPerConnection);

        var beans = context.getBeansWithAnnotation(RpcService.class);
        int i = 0;
        for (var kv : beans.entrySet()) {
            var bean = kv.getValue();
            var beanName = kv.getKey();
            if (Proxy.isProxyClass(bean.getClass())) {
                log.debug("ignore Client RPC : {}", beanName);
                continue;
            }

            var filterList = new ArrayList<ServerFilter>();
            var fa = context.findAnnotationOnBean(beanName, Filters.class);
            if (null != fa) {
                for (var f : fa.value()) {
                    var flt = context.getBean(f);
                    filterList.add(flt);
                }
            }
            proxyServerBuilder.addService(bean, filterList);
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

    @PreDestroy
    public void stop() {

        var waitTask = 0;
        // C6 + AUD-omp-11: graceful drain via the single shutdown authority (was a bare shutdownNow()
        // that cut in-flight RPCs). Server drains first, THEN the executor is released.
        if (null != server) {
            RpcServerBuilder.shutdown(server, RpcServerBuilder.DEFAULT_SHUTDOWN_GRACE);
        }
        if (null != executor) {
            waitTask = executor.shutdownNow().size();
        }
        server = null;
        log.info("***** 【 {} 】 RpcServer shutting down with {} waiting tasks, since JVM is shutting down.", EnvUtils.current(), waitTask);
    }

    //public boolean isRunning() {
    //    return server != null;
    //}

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        log.debug("*******************rpc.enable RpcServerAutoConfigure*******************************");
        log.debug(" app = {} , port = {}", app, port);
        log.debug(" validator = {}", validator);

        initFilter(ctx);

        initValidator();

        initJwsVerify.init();

        // OTEL-001 (ADR-0006): explicitly install the Spring context's OpenTelemetry into krpc (no
        // global probing). ifAvailable => absent (no OTel starter) leaves krpc no-op.
        ctx.getBeanProvider(OpenTelemetry.class).ifAvailable(otel -> {
            KrpcOtel.install(otel);
            log.info("OTel: installed Spring OpenTelemetry into krpc (tracing active).");
        });

        if (!defaultExecutor) {
            var name = app + "-rpc";
            executor = ThreadPool.newExecutor(name);
            log.info("Init virtual-thread per-task executor {}, replacing grpc ServerImplBuilder.DEFAULT_EXECUTOR_POOL", name);
        } else {
            log.info("Use CachedThreadPool ServerImplBuilder.DEFAULT_EXECUTOR_POOL grpc-default-executor.");
        }

        try {
            int serviceSize = startServer(ctx);
            log.info("***** 【 {} 】 RpcServer Expose {} Services on {}, {}.", EnvUtils.current(), serviceSize, port,
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

    //
    //@Bean
    //public InitJwsVerify initJwsVerify()  {
    //    log.debug("*******************【 USE InitJwsVerify 】******************************* ");
    //    return new InitJwsVerify();
    //}
}