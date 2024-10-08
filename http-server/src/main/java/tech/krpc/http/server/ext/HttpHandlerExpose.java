/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.http.server.ext;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.validation.Validator;

import tech.krpc.http.server.AbstractHttpHandler;
import tech.krpc.http.server.GetHandler;
import tech.krpc.http.server.HttpServer;
import tech.krpc.http.server.PostHandler;
import tech.krpc.util.EnvUtils;
import io.quarkus.runtime.Startup;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 *
 * @author Martin.C
 * @version 2021/12/22 11:30 AM
 */
@ApplicationScoped
@Startup
//@Sharable
@Slf4j
public class HttpHandlerExpose extends AbstractHttpHandler {

    @ConfigProperty(name = "http.port", defaultValue = "8080")
    int port;

    @Inject
    Validator validator;

    HttpServer httpServer;

    @Override
    @PostConstruct
    public void initHandler() {
        var bm = CDI.current().getBeanManager();
        //var getSet = bm.getBeans(GetHandler.class);
        //var postSet = bm.getBeans(PostHandler.class);

        var allSet = bm.getBeans(Object.class, new AnnotationLiteral<Any>() {});
        //var allGetSet = bm.getBeans(GetHandler.class, new AnnotationLiteral<Any>() {});
        //var allPostSet = bm.getBeans(PostHandler.class, new AnnotationLiteral<Any>() {});

        int g = 0,p = 0;
        for (var bean : allSet){
            if(GetHandler.class.isAssignableFrom(bean.getBeanClass())){
                g++;
                var handler = (GetHandler) CDI.current().select(bean.getBeanClass()).get();
                getHanlderMap.put(handler.path(), handler);
                log.debug(" found HTTP GET {} ", handler.path());
            }

            if(PostHandler.class.isAssignableFrom(bean.getBeanClass())){
                p++;
                var handler = (PostHandler<?>) CDI.current().select(bean.getBeanClass()).get();
                postMap.put(handler.path(), handler);
                log.debug(" found HTTP POST {} ", handler.path());
            }
        }
        if(g+p == 0){
            log.info(" Skip HTTP Server , no Handlers found. ");
            return;
        }

        httpServer = new HttpServer(this, port);
        try {
            httpServer.start();
            if (!getHanlderMap.isEmpty()) {
                log.info(" GET {}", getHanlderMap.keySet());
            }
            if (!postMap.isEmpty()) {
                log.info(" POST {}", postMap.keySet());
            }
            log.info("***** 【 {} 】 HTTP Server {} endpoints  on {}",
                    EnvUtils.current(), g + p, port);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (null != httpServer) {
            httpServer.shutdown();
        }
    }

    @Override
    public Validator getValidator() {
        return validator;
    }
}