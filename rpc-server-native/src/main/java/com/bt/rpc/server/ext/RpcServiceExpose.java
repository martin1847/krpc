package com.bt.rpc.server.ext;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import com.bt.rpc.filter.GlobalFilter;
import com.bt.rpc.filter.GlobalFilter.Order;
import com.bt.rpc.server.Filters;
import com.bt.rpc.server.ReflectionHelper;
import com.bt.rpc.server.RpcServerBuilder;
import com.bt.rpc.server.ServerContext;
import com.bt.rpc.server.ServerFilter;
import com.bt.rpc.server.jws.ExtVerify;
import com.bt.rpc.server.jws.JwsVerify;
import com.bt.rpc.util.EnvUtils;
import io.grpc.Server;
import io.quarkus.runtime.Startup;
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

    @Inject
    Validator validator;
    //
    //@Inject
    //Instance<Validator> validator2;


    //void onStart(@Observes StartupEvent ev) {
    //    log.info("use to not remove Startup ");
    //
    //    //https://github.com/eugenp/tutorials/blob/master/quarkus-jandex/hello-sender-beans-xml/src/main/java/com/baeldung/quarkus/hello/sender/beansxml/BeansXmlHelloSender.java
    //    //event.getHelloReceiver().accept("Hi, I was detected using an empty META-INF/beans.xml file.");
    //}

    //static final String NULL = "null";

    @ConfigProperty(name = "rpc.server.app")//,defaultValue = NULL
    Optional<String> app;

    @ConfigProperty(name = "rpc.server.jwks")//,defaultValue = NULL
    Optional<String> jwks;


    @ConfigProperty(name = "rpc.server.jwtCookie",defaultValue = JwsVerify.DEFAULT_COOKIE_NAME)
    String jwtCookie;

    @ConfigProperty(name = "rpc.server.exitOnJwksError",defaultValue = "false")
    boolean exitOnJwksError;


    @ConfigProperty(name = "rpc.server.port",defaultValue = RpcConstants.DEFAULT_PORT+"")
    int port;

    @PostConstruct
    public void expose() throws Exception {

        initFilter();

        initValidator();

        initJwsVerify();

        //System.getProperties().forEach((k,v)->{
        //    System.out.println( k + "$$$$$$$$$$$$" + v);
        //});

        var app = autoAppName();
        var serviceSize = initServer(app);
        log.info("***** 【 {} 】 RpcServer expose {} services on {}, {}.", EnvUtils.current(),serviceSize, port, RpcConstants.CI_BUILD_ID);
    }

    @PreDestroy
    public void shutdown() {
        if (null != server) {
            server.shutdownNow();
        }
        log.info("***** 【 {} 】 RpcServer shutting down , since JVM is shutting down.", EnvUtils.current());
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

    void initJwsVerify(){
        if(jwks.isEmpty()){
            log.info("No Jwks Url Set, Skip.");
            return;
        }
        var url = jwks.get();
        ExtVerify ext = ExtVerify.EMPTY;
        var extVerifies = CDI.current().select(ExtVerify.class);
        if (extVerifies.isResolvable()) {
            ext = extVerifies.get();
        }
        var cookieName = jwtCookie;//rpcConfig.jwtCookie().orElse(JwsVerify.DEFAULT_COOKIE_NAME);
        log.info("Reg CredentialVerify: {} ,cookieName: {}", url, cookieName);
        if (ext != ExtVerify.EMPTY) {
            log.info("Reg Customer ExtVerify AfterJwsSignCheck :  {} ", ext);
        }
        var jwks = new JwsVerify(url, cookieName, ext);
        try {
            jwks.loadJwks();
        } catch (RuntimeException e) {
            if (exitOnJwksError) {
                throw e;
            } else {
                log.warn("Error load jwks {} , {}", jwks.getUrl(), e.getMessage());
            }
        }
        ServerContext.regCredentialVerify(jwks);

    }


    int initServer(String app) throws Exception {
        var proxyServerBuilder = new RpcServerBuilder.Builder(app, port);

        var bm = CDI.current().getBeanManager();
        Set<Bean<?>> beans = bm.getBeans(Object.class, new AnnotationLiteral<Any>() {});
        int i = 0;
        for (Bean<?> bean : beans) {

            List<Class> effectiveClassAnnotations = ReflectionHelper.getEffectiveClassAnnotations(bean.getBeanClass(), RpcService.class);

            if (effectiveClassAnnotations != null && effectiveClassAnnotations.size() > 0) {

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
                }
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
            log.info("Reg GlobalValidator :  {}", validator);
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
            log.info("Reg GlobalFilter :  {}", k);
            ServerContext.regGlobalFilter(v);
        });
    }

}
