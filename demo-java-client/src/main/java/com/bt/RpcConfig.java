package com.bt;

import io.smallrye.config.ConfigMapping;

import java.util.Optional;

@ConfigMapping(prefix = "rpc")
public interface RpcConfig {
    /**
     * 是否开启dubbo
     */
    Optional<Boolean> enabled();


    int port();

    /**
     * app name
     */
    String name() ;

    /**
//     * 注册中心地址
//     */
//    @ConfigItem
//    public Optional<String> registrAddr;

    Protocol protocol();

    interface Protocol {

        /**
         * 端口
         */
        Optional<Integer> port();


    }
}
