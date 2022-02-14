/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.bt.rpc.server.ext;

import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.spi.CDI;
import javax.inject.Named;

import com.bt.rpc.server.ServerContext;
import com.bt.rpc.server.jws.ExtVerify;
import com.bt.rpc.server.jws.JwsVerify;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 *
 * @author Martin.C
 * @version 2022/02/14 1:32 PM
 */
@ApplicationScoped
@Slf4j
@Named("DefaultJwsVerify")
public class InitJwsVerify {


    public static final String JWKS_CONFIG_KEY = "rpc.server.jwks";
    public static final String JWS_COOKIE_CONFIG_KEY = "rpc.server.jwsCookie";

    public static final String EXIT_ON_JWKS_ERROR_CONFIG_KEY = "rpc.server.exitOnJwksError";


    @ConfigProperty(name = JWKS_CONFIG_KEY)//,defaultValue = NULL
    Optional<String> jwks;


    @ConfigProperty(name = JWS_COOKIE_CONFIG_KEY,defaultValue = JwsVerify.DEFAULT_COOKIE_NAME)
    String jwsCookie;

    @ConfigProperty(name = EXIT_ON_JWKS_ERROR_CONFIG_KEY,defaultValue = "false")
    boolean exitOnJwksError;


    void init(){
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
        var cookieName = jwsCookie;//rpcConfig.jwtCookie().orElse(JwsVerify.DEFAULT_COOKIE_NAME);
        log.info("Init CredentialVerify: {} ,cookieName: {}", url, cookieName);
        if (ext != ExtVerify.EMPTY) {
            log.info("Reg Customer ExtVerify AfterJwsSignCheck :  {} ", ext);
        }
        var jwks = new JwsVerify(url, cookieName, ext);
        try {
            jwks.loadJwks();
            ServerContext.regCredentialVerify(jwks);
        } catch (RuntimeException e) {
            if (exitOnJwksError) {
                throw e;
            } else {
                log.warn("!!! Error load jwks {} , auth token check NOT work : {}", jwks.getUrl(), e.getMessage());
            }
        }
    }

}