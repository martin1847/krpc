package com.bt;

import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.server.ReflectionHelper;
import com.bt.rpc.server.RpcServerBuilder;
import io.grpc.Server;
import io.quarkus.runtime.Startup;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.CDI;
import javax.enterprise.util.AnnotationLiteral;
import javax.inject.Inject;
import java.util.List;
import java.util.Set;

/**
 * @author kl : http://kailing.pub
 * @version 1.0
 * @date 2020/9/25
 */
@ApplicationScoped
@Startup
@Slf4j
public class RpcServiceExport {//} extends SimpleBuildItem{


    @Inject
    RpcConfig rpcConfig;

    Server server;

    @PostConstruct
    public void exportStart() throws Exception {
        Set<Bean<?>> beans = CDI.current().getBeanManager().getBeans(Object.class, new AnnotationLiteral<Any>() {
        });


        var proxyServerBuilder = new RpcServerBuilder.Builder(rpcConfig.name(), rpcConfig.port());


        for (Bean<?> bean : beans) {

            List<Class> effectiveClassAnnotations = ReflectionHelper.getEffectiveClassAnnotations(bean.getBeanClass(), RpcService.class);

            if (effectiveClassAnnotations != null && effectiveClassAnnotations.size()>0) {
                Instance<?> instance = CDI.current().select(bean.getBeanClass());
                proxyServerBuilder.addService(instance.get());
                log.info("finding Rpc service =>:{}",bean.getBeanClass());
            }
        }
        server = proxyServerBuilder.build().startServer();
        log.info("RpcServer started, listening on " + rpcConfig.port());
    }


    @PreDestroy
    public void shutdown(){
        server.shutdown();
        log.info("*** shutting down RPC server since JVM is shutting down");
    }
}
