/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package tech.krpc.server.spring;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
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

    public static final String JWS_AUD_CONFIG_KEY = "rpc.server.jwsAudiences";


    @Setter
    Optional<String> jwks;

    @Setter
    String jwsCookie = JwsVerify.DEFAULT_COOKIE_NAME;
    @Setter
    boolean exitOnJwksError;
    @Setter
    boolean bindClient;
    // O-sec-17 (HARDEN-B1): optional aud validation, comma-separated. Empty = OFF (default).
    @Setter
    List<String> jwsAudiences = List.of();


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

        // O1 (HARDEN-B1): construct + FAIL-CLOSED bootstrap + register in core. The verifier is
        // always registered (unless exitOnJwksError aborts startup), so a JWKS outage can never
        // leave auth fail-open.
        JwsVerify.bootstrapAndRegister(url, cookieName, extVerify, bindClient, exitOnJwksError,
                jwsAudiences);
    }

}