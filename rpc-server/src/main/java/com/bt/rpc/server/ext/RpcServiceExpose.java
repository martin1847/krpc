package com.bt.rpc.server.ext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.CDI;
import javax.enterprise.util.AnnotationLiteral;
import javax.inject.Inject;
import javax.validation.Validator;

import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.server.Filters;
import com.bt.rpc.filter.GlobalFilter;
import com.bt.rpc.filter.GlobalFilter.Order;
import com.bt.rpc.server.ReflectionHelper;
import com.bt.rpc.server.RpcServerBuilder;
import com.bt.rpc.server.ServerContext;
import com.bt.rpc.server.ServerFilter;
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
    public void expose() throws Exception {
        var bm = CDI.current().getBeanManager();
        Set<Bean<?>> beans = bm.getBeans(Object.class, new AnnotationLiteral<Any>() {});
        var sfSet = bm.getBeans(ServerFilter.class);
        var map = new TreeMap<GlobalFilter.Order,ServerFilter>();
        sfSet.forEach(bean->{
            var gf = bean.getBeanClass().getAnnotation(GlobalFilter.class);
            if(null!=gf){
                map.put(new Order(gf.value(), bean.getBeanClass().getSimpleName()),
                        (ServerFilter)CDI.current().select(bean.getBeanClass()).get());
            }
        });
        map.forEach((k,v)->{
            log.info("Reg GlobalFilter :  {}" , k);
            ServerContext.regGlobalFilter(v);
        });

        var validators =  CDI.current().select(Validator.class);
        if(validators.isResolvable()){
            log.info("Reg GlobalValidator :  {}" , validators.get());
            ServerContext.regValidator(validators.get());
        }


        var proxyServerBuilder = new RpcServerBuilder.Builder(rpcConfig.name(), rpcConfig.port());

        int i = 0;
        for (Bean<?> bean : beans) {

            List<Class> effectiveClassAnnotations = ReflectionHelper.getEffectiveClassAnnotations(bean.getBeanClass(), RpcService.class);

            if (effectiveClassAnnotations != null && effectiveClassAnnotations.size() > 0) {


                var fa = bean.getBeanClass().getAnnotation(Filters.class);
                var filterList = new ArrayList<ServerFilter>();
                if(null!=fa){
                   for(var f:   fa.value()){
                       var fins =  CDI.current().select(f);
                       if(fins.isResolvable()){
                           filterList.add((ServerFilter) fins.get());
                       }else if(fa.ignoreNotFound()){
                           log.warn("Filters For Service {} Not Found :  {}" ,bean.getBeanClass(), f);
                       }else{
                           throw new RuntimeException("Filters Not Found :  " + f);
                       }
                   }
                }
                Instance<?> instance = CDI.current().select(bean.getBeanClass());
                proxyServerBuilder.addService(instance.get(),filterList);
                i++;
                log.info("Found Rpc Service :=> {} , with filters {} ",bean.getBeanClass(),filterList);
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
