package com.bt.rpc.server.ext;

import java.util.List;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.CDI;
import javax.enterprise.util.AnnotationLiteral;
import javax.inject.Inject;

import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.server.ReflectionHelper;
import com.bt.rpc.server.RpcServerBuilder;
import com.bt.rpc.server.ext.RpcConfig;
import io.grpc.Server;
import io.quarkus.runtime.Startup;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Martin.C
 */
@ApplicationScoped
@Startup
@Slf4j
public class RpcServiceExpose {//} extends SimpleBuildItem{

    @Inject
    RpcConfig rpcConfig;

    Server server;

    @PostConstruct
    //@BuildStep
    //@Record(ExecutionTime.RUNTIME_INIT)
    // RpcConfig rpcConfig
    public void expose() throws Exception {
        Set<Bean<?>> beans = CDI.current().getBeanManager().getBeans(Object.class, new AnnotationLiteral<Any>() {
        });

        var proxyServerBuilder = new RpcServerBuilder.Builder(rpcConfig.name(), rpcConfig.port());

        int i = 0;
        for (Bean<?> bean : beans) {

            List<Class> effectiveClassAnnotations = ReflectionHelper.getEffectiveClassAnnotations(bean.getBeanClass(), RpcService.class);

            if (effectiveClassAnnotations != null && effectiveClassAnnotations.size() > 0) {
                Instance<?> instance = CDI.current().select(bean.getBeanClass());
                proxyServerBuilder.addService(instance.get());
                i++;
                log.info("Found Rpc Service :=> {}",bean.getBeanClass());
            }

        }
        server = proxyServerBuilder.build().startServer();
        log.info("*** RpcServer started with {} services, listening on {}" ,i, rpcConfig.port());
    }

    @PreDestroy
    public void shutdown() {
        if (null != server) {
            server.shutdownNow();
        }
        log.info("*** RpcServer shutting down , since JVM is shutting down");
    }
}
