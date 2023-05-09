package com.bt.rpc.server.ext;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;

import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.common.RpcConstants;
import com.bt.rpc.filter.GlobalFilter;
import com.bt.rpc.filter.GlobalFilter.Order;
import com.bt.rpc.server.Filters;
import com.bt.rpc.server.ReflectionHelper;
import com.bt.rpc.server.RpcServerBuilder;
import com.bt.rpc.server.ServerContext;
import com.bt.rpc.server.ServerFilter;
import com.bt.rpc.server.exe.ThreadPool;
import com.bt.rpc.util.EnvUtils;
import io.grpc.Server;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * @author Martin.C
 */
@ApplicationScoped
@Startup
@Slf4j
public class RpcServiceExpose {//} extends SimpleBuildItem{

    //@Inject
    //RpcConfig rpcConfig;

    Server server;

    ExecutorService executor;

    @Inject
    Validator validator;

    @Inject
    InitJwsVerify initJwsVerify;

    @ConfigProperty(name = "rpc.server.app")//,defaultValue = NULL
    Optional<String> app;

    @ConfigProperty(name = "rpc.server.port",defaultValue = RpcConstants.DEFAULT_PORT+"")
    int port;

    @ConfigProperty(name = "rpc.server.defaultExecutor",defaultValue = "false")
    boolean defaultExecutor;

    @PostConstruct
    public void expose() throws Exception {

        initFilter();

        initValidator();

        initJwsVerify.init();

        //System.getProperties().forEach((k,v)->{
        //    System.out.println( k + "$$$$$$$$$$$$" + v);
        //});

        var app = autoAppName();

        if(!defaultExecutor) {
            // instead of ServerImplBuilder.DEFAULT_EXECUTOR_POOL ( SHARED_CHANNEL_EXECUTOR/ NAME = "grpc-default-executor")
            var name = app + "-rpc";
            var cpus = Runtime.getRuntime().availableProcessors();
            if(cpus < 4 ){
                // docker may be 1
                log.info(" cpus is too small {} , change to default 4.",cpus);
                cpus = 4;
            }
            executor = ThreadPool.newExecutor(name, cpus);
            log.info("Init Executor {}({} cpus),  instead of ServerImplBuilder.DEFAULT_EXECUTOR_POOL", name, cpus);
        }else {
            log.info("Use CachedThreadPool ServerImplBuilder.DEFAULT_EXECUTOR_POOL grpc-default-executor.");
        }

        var serviceSize = initServer(app);
        log.info("***** 【 {} 】 RpcServer expose {} services on {}, {}.", EnvUtils.current(),serviceSize, port, RpcConstants.CI_BUILD_ID);
    }

    @PreDestroy
    public void shutdown() {
        var waitTask = 0;
        if (null != server) {
            server.shutdownNow();
        }
        if(null != executor){
            waitTask = executor.shutdownNow().size();
        }
        log.info("***** 【 {} 】 RpcServer shutting down with {} waiting tasks, since JVM is shutting down.", EnvUtils.current(),waitTask);
    }


    String autoAppName(){
        if(app.isPresent()){
            return app.get();
        }
        var podName = System.getenv("HOSTNAME");
        int split;
        if (null != podName && (split = podName.lastIndexOf('-')) > 0) {
            if ((split = podName.lastIndexOf('-', split - 1)) > 0) {
                return podName.substring(0, split);
            }
        }
        //java.class.path   = /Users/garden/bt-rpc/test-server/build/test-server-dev.jar
        var path = System.getProperty("java.class.path");
        var fileSplit = File.separatorChar;
        var buildFLag = fileSplit + "build" + fileSplit;

        if ((split = path.indexOf(buildFLag)) <= 0) {
            path = System.getProperty("native.image.path");
            split = path.indexOf(buildFLag);
        }

        if (split > 0) {
            return path.substring(path.lastIndexOf(fileSplit, split - 1) + 1, split);
        }

        throw new RuntimeException("pls SET the rpc.server.app in application.properties");
    }



    int initServer(String app) throws Exception {
        var proxyServerBuilder = new RpcServerBuilder.Builder(app, port);

        proxyServerBuilder.executor(executor);

        var bm = CDI.current().getBeanManager();
        Set<Bean<?>> beans = bm.getBeans(Object.class, new AnnotationLiteral<Any>() {});
        int i = 0;
        for (Bean<?> bean : beans) {

            // jandex 1.0 changed, now return all class include external jars.
            var otherAppOrNotRpcBean = bean.getTypes().size() < 3 || bean.getQualifiers().stream().anyMatch(an -> {
                if (an instanceof Named) {
                    var oth = ((Named) an).value();
                    return oth != null && !oth.equals(app);
                }
                return false;
            });

            if(otherAppOrNotRpcBean){
                continue;
            }

            List<Class> effectiveClassAnnotations = ReflectionHelper.getEffectiveClassAnnotations(bean.getBeanClass(), RpcService.class);

            if(null == effectiveClassAnnotations || effectiveClassAnnotations.isEmpty()){
                continue;
            }

            var fa = bean.getBeanClass().getAnnotation(Filters.class);
            var filterList = new ArrayList<ServerFilter>();
            if (null != fa) {
                for (var f : fa.value()) {
                    var fins = CDI.current().select(f);
                    if (fins.isResolvable()) {
                        filterList.add((ServerFilter) fins.get());
                    } else if (fa.ignoreNotFound()) {
                        log.warn("Filters For Service {} Not Found :  {}", bean.getBeanClass(), f);
                    } else {
                        throw new RuntimeException("Filters Not Found :  " + f);
                    }
                }
            }
            Instance<?> instance = CDI.current().select(bean.getBeanClass());
            proxyServerBuilder.addService(instance.get(), filterList);
            i++;
            if (filterList.size() > 0) {
                log.info("Found Rpc Service :=> {} , with filters {} ", bean.getBeanClass(), filterList);
            }else {
                log.debug("Found Rpc Service :=> {} no filters.", bean.getBeanClass());
            }
        }
        server = proxyServerBuilder.build().startServer();
        return i;
    }

    public void initValidator(){
        //var validators = CDI.current().select(Validator.class);
        //if (validators.isResolvable()) {
        if (validator instanceof EmptyValidator) {
            log.warn("EmptyValidator Found, All Rpc Validator will Skip, Are you Sure?");
        }else {
            log.info("[ Reg GlobalValidator ] :  {}", validator);
            ServerContext.regValidator(validator);
        }
    }


    public void initFilter(){
        var bm = CDI.current().getBeanManager();
        var sfSet = bm.getBeans(ServerFilter.class);
        var map = new TreeMap<GlobalFilter.Order, ServerFilter>();
        sfSet.forEach(bean -> {
            var gf = bean.getBeanClass().getAnnotation(GlobalFilter.class);
            if (null != gf) {
                map.put(new Order(gf.value(), bean.getBeanClass().getSimpleName()),
                        (ServerFilter) CDI.current().select(bean.getBeanClass()).get());
            }
        });
        map.forEach((k, v) -> {
            log.info("[ Reg GlobalFilter ] :  {}", k);
            ServerContext.regGlobalFilter(v);
        });
    }

}
