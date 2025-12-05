/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package tech.krpc.server.spring;

import java.util.Optional;

import jakarta.inject.Inject;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import tech.krpc.server.ServerContext;
import tech.krpc.server.jws.ExtVerify;
import tech.krpc.server.jws.JwsVerify;

/**
 *
 * @author Martin.C
 * @version 2022/02/14 1:32 PM
 */
@Slf4j
@ConfigurationProperties(prefix = "rpc.server")
public class InitJwsVerify {


    public static final String JWKS_CONFIG_KEY = "rpc.server.jwks";
    public static final String JWS_COOKIE_CONFIG_KEY = "rpc.server.jwsCookie";

    public static final String JWS_BIND_CONFIG_KEY = "rpc.server.jwsBindClient";

    public static final String EXIT_ON_JWKS_ERROR_CONFIG_KEY = "rpc.server.exitOnJwksError";


    @Setter
    Optional<String> jwks;

    @Setter
    String jwsCookie = JwsVerify.DEFAULT_COOKIE_NAME;
    @Setter
    boolean exitOnJwksError;
    @Setter
    boolean bindClient;


    @Inject
    ExtVerify extVerify;


    void init(){
        if(null == jwks ||  jwks.isEmpty()){
            log.info("No Jwks Url Set, Skip.");
            return;
        }
        var url = jwks.get();
        //ExtVerify ext = ExtVerify.EMPTY;
        //var extVerifies = CDI.current().select(ExtVerify.class);
        //if (extVerifies.isResolvable()) {
        //    ext = extVerifies.get();
        //}
        var cookieName = jwsCookie;//rpcConfig.jwtCookie().orElse(JwsVerify.DEFAULT_COOKIE_NAME);
        log.info("Init Credential JwsVerify: {} ,cookieName: {} , bindClient: {}", url, cookieName, bindClient);
        //if (ext != ExtVerify.EMPTY) {
        //    log.info("Reg Customer ExtVerify AfterJwsSignCheck :  {} ", ext);
        //}
        if (extVerify instanceof EmptyExtVerify) {
            log.debug("Skip ExtVerify...");
        }else {
            log.info("[ Reg ExtVerify ] :  {}", extVerify);
        }

        var jwks = new JwsVerify(url, cookieName, extVerify,bindClient);
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