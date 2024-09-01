/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package tech.krpc.client.spring;

import java.util.Map;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 *
 * @author martin
 * @version 2023/12/12 16:30
 */
@Configuration
@AutoConfiguration
@ConditionalOnProperty(name = "rpc.enable")
@Slf4j
//@EnableConfigurationProperties
//@ConfigurationProperties(prefix = "rpc")
public class RpcClientAutoConfigure implements InitializingBean {
    //
    //@Getter
    //@Setter
    //private Map<String, RpcClientConfig> clients;

    //@Bean
    //@ConfigurationProperties(prefix = "rpc")
    ////@ConditionalOnMissingBean({  RpcClientConfig.class })
    //public Map<String, RpcClientProperties> clients() {
    //    return new HashMap<>();
    //}
    public static final String PREFIX = "rpc";

    @Data
    @ConfigurationProperties(prefix = PREFIX)
    public static class RpcClientProperties {
        private Map<String, RpcCfg> clients;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        log.debug("*******************rpc.enable RpcClientAutoConfigure*******************************");
        //log.info("*******************RpcClientAutoConfigure*******************************");
        //log.info("*******************RpcClientAutoConfigure*******************************");
        //log.info("*******************RpcClientAutoConfigure*******************************");
        //log.info("*******************RpcClientAutoConfigure******************************* {}",clients);
    }


    //
    //@org.springframework.context.annotation.Configuration
    //@Import(RpcClientScannerConfigurer.class)
    //@ConditionalOnMissingBean({  RpcClientScannerConfigurer.class })
    //@Slf4j
    //public static class MapperScannerRegistrarNotFoundConfiguration implements InitializingBean {
    //
    //    @Override
    //    public void afterPropertiesSet() {
    //        log.info("*******************MapperScannerRegistrarNotFoundConfiguration******************************* ");
    //        log.info("*******************MapperScannerRegistrarNotFoundConfiguration******************************* ");
    //        log.info("*******************MapperScannerRegistrarNotFoundConfiguration******************************* ");
    //        log.info("*******************MapperScannerRegistrarNotFoundConfiguration******************************* ");
    //        log.info(
    //                "Not found configuration for registering mapper bean using @MapperScan, MapperFactoryBean and MapperScannerConfigurer.");
    //    }
    //
    //}
    //
    @Bean
    @ConditionalOnMissingBean({  RpcClientScannerConfigurer.class })
    public RpcClientScannerConfigurer autoRpcClientScannerConfigurer(Environment environment) throws Exception {
        //log.info("*******************RpcClientScannerConfigurerNotFoundConfiguration******************************* ");
        //log.info("*******************RpcClientScannerConfigurerNotFoundConfiguration******************************* ");
        //log.info("*******************RpcClientScannerConfigurerNotFoundConfiguration******************************* ");
        //log.info("*******************RpcClientScannerConfigurerNotFoundConfiguration******************************* ");
        //log.info("*******************RpcClientScannerConfigurerNotFoundConfiguration******************************* ");
        Binder binder = Binder.get(environment);
        var properties = binder.bind(PREFIX, RpcClientProperties.class).get();

        log.debug("RpcClientScannerConfigurer Not found ,build from yaml {}",properties);
        var cfg = new RpcClientScannerConfigurer();
        cfg.setClients(properties.getClients());
        return cfg;
    }
}