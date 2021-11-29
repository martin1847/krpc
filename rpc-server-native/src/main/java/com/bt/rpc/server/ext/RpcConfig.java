package com.bt.rpc.server.ext;

import io.smallrye.config.ConfigMapping;

import java.util.Optional;

@ConfigMapping(prefix = "rpc.server")
public interface RpcConfig {
    /**
     */
    //Optional<String> globalFilter();


    Optional<Integer> port();

    /**
     * app name
     */
    Optional<String> app() ;


    Optional<String> jwks() ;

    Optional<String> jwtCookie() ;

    Optional<Boolean> exitOnJwksError() ;
}
