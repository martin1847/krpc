package com.bt;

import io.quarkus.runtime.Startup;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.CDI;
import javax.enterprise.util.AnnotationLiteral;
import javax.inject.Inject;
import java.util.Set;

/**
 * @author kl : http://kailing.pub
 * @version 1.0
 * @date 2020/9/25
 */
@ApplicationScoped
@Startup
@Slf4j
public class RpcAutoScan {//} extends SimpleBuildItem{


    @Inject
    RpcConfig grpcConfig ;



//
////        System.out.println(" [ Grpc Epoll ] :" + Epoll.isAvailable());
//
////        alization of this class with the following trace:
////        at io.netty.util.AbstractReferenceCounted.<clinit>(AbstractReferenceCounted.java:26)
////        at java.lang.Class.forName0(Unknown Source)
////        at java.lang.Class.forName(Class.java:398)
////        at io.netty.util.internal.ClassInitializerUtil.tryLoadClass(ClassInitializerUtil.java:41)
////        at io.netty.util.internal.ClassInitializerUtil.tryLoadClasses(ClassInitializerUtil.java:34)
////        at io.netty.channel.epoll.Native.<clinit>(Native.java:72)
////        at io.netty.channel.epoll.Epoll.<clinit>(Epoll.java:39)
//    }

    @PostConstruct
    public void exportStart() throws Exception {
        Set<Bean<?>> beans = CDI.current().getBeanManager().getBeans(Object.class, new AnnotationLiteral<Any>() {
        });


//        var proxyServerBuilder = new RpcServerBuilder.Builder(grpcConfig.name(),grpcConfig.port());
//
//
//        for (Bean<?> bean : beans) {
//
//            List<Class> effectiveClassAnnotations = ReflectionHelper.getEffectiveClassAnnotations(bean.getBeanClass(), GrpcService.class);
//
//            if (effectiveClassAnnotations != null && effectiveClassAnnotations.size()>0) {
//                Instance<?> instance = CDI.current().select(bean.getBeanClass());
//                proxyServerBuilder.addService(instance.get());
//                log.info("finding Grpc service =>:{}",bean.getBeanClass());
//            }
//        }
//        server = proxyServerBuilder.build().startServer();

        TestJavaProxyClient.main(new String[]{});
        log.info("RpcClient Init {} Service " , 0);
    }


    @PreDestroy
    public void shutdown(){
        log.info("*** shutting down RPC client RpcAutoScan since JVM is shutting down");
    }
}
