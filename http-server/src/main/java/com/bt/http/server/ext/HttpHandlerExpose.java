/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.http.server.ext;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.spi.CDI;
import javax.inject.Inject;
import javax.validation.Validator;

import com.bt.http.server.AbstractHttpHandler;
import com.bt.http.server.GetHandler;
import com.bt.http.server.HttpServer;
import com.bt.http.server.PostHandler;
import com.bt.rpc.util.EnvUtils;
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
        var getSet = bm.getBeans(GetHandler.class);
        var postSet = bm.getBeans(PostHandler.class);

        if(getSet.isEmpty() && postSet.isEmpty()){
            log.info(" Skip HTTP Server , no Handlers found. ");
            return;
        }

        getSet.forEach(bean -> {
            var handler = (GetHandler) CDI.current().select(bean.getBeanClass()).get();
            getHanlderMap.put(handler.path(), handler);
            log.info("reg HTTP GET {} ", handler.path());
        });

        postSet.forEach(bean -> {
            var handler = (PostHandler) CDI.current().select(bean.getBeanClass()).get();
            postMap.put(handler.path(), handler);
            log.info("reg HTTP POST {} ", handler.path());
        });

        httpServer = new HttpServer(this, port);
        try {
            httpServer.start();
            log.info("***** 【 {} 】 HTTP Server , {}GET {}POST on {}",
                    EnvUtils.current(),getSet.size(),postSet.size(),port);
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