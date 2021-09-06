package com.bt.rpc.server.ext;

import io.smallrye.config.ConfigMapping;

import java.util.Optional;

@ConfigMapping(prefix = "rpc.server")
public interface RpcConfig {
    /**
     */
    Optional<String> globalFilter();


    int port();

    /**
     * app name
     */
    String name() ;

    /**
//     */
//    @ConfigItem
//    public Optional<String> registrAddr;
//
//    Protocol protocol();
//
//    interface Protocol {
//
//        /**
//         * 端口
//         */
//        Optional<Integer> port();
//
//
//    }
}
