package com.bt.rpc.server.ext;

import java.io.File;
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
import com.bt.rpc.common.RpcConstants;
import com.bt.rpc.server.Filters;
import com.bt.rpc.filter.GlobalFilter;
import com.bt.rpc.filter.GlobalFilter.Order;
import com.bt.rpc.server.ReflectionHelper;
import com.bt.rpc.server.RpcServerBuilder;
import com.bt.rpc.server.ServerContext;
import com.bt.rpc.server.ServerFilter;
import com.bt.rpc.server.jws.JwksVerify;
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

        rpcConfig.jwks().ifPresent(url->{
            log.info("Reg CredentialVerify :  {}" , url);
            var jwks = new JwksVerify(url);
            try {
                jwks.loadJwks();
            }catch (RuntimeException e){
                if (Boolean.TRUE.equals(rpcConfig.exitOnJwksError().orElse(Boolean.FALSE))){
                    throw e;
                }else {
                    log.warn("error load jwks {} , {}",jwks.getUrl(),e.getMessage());
                }
            }
            ServerContext.regCredentialVerify(jwks);
        });

        //System.getProperties().forEach((k,v)->{
        //    System.out.println( k + "$$$$$$$$$$$$" + v);
        //});
        var app =  rpcConfig.app().orElseGet(()->{
            //HOSTNAME=demo-java-server-ddc6cc976-sm6pn
            var podName = System.getenv("HOSTNAME");
            int split;
            if(null != podName && (split= podName.lastIndexOf('-')) > 0){
                if((split= podName.lastIndexOf('-',split - 1)) > 0 ){
                    return podName.substring(0,split);
                }
            }
            //java.class.path   = /Users/garden/bt-rpc/test-server/build/test-server-dev.jar
            var path = System.getProperty("java.class.path");
            var fileSplit = File.separatorChar;
            var buildFLag = fileSplit+"build"+fileSplit;

            if((split = path.indexOf(buildFLag)) <= 0){
                path =  System.getProperty("native.image.path");
                split = path.indexOf(buildFLag);
            }

            if(split > 0){
                return path.substring(path.lastIndexOf(fileSplit,split-1) +1,split);
            }

            throw new RuntimeException("pls SET the rpc.server.app in application.properties");
        });

        var port = rpcConfig.port().orElse(RpcConstants.DEFAULT_PORT);
        var proxyServerBuilder = new RpcServerBuilder.Builder(app, port);

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
        log.info("***** RpcServer started with {} services, listening on {}." ,i, port);
    }

    @PreDestroy
    public void shutdown() {
        if (null != server) {
            server.shutdownNow();
        }
        log.info("***** RpcServer shutting down , since JVM is shutting down.");
    }

}
