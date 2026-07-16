/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package tech.krpc.client.spring;
import java.util.Map;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tech.krpc.client.ClientDeadline;

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

    @Autowired
    Environment environment;

    // OTEL-001 (ADR-0006): the Spring context's OpenTelemetry, if present (micrometer-tracing /
    // OTel starter). ObjectProvider so a client-only app without an OTel starter stays no-op.
    @Autowired(required = false)
    org.springframework.beans.factory.ObjectProvider<io.opentelemetry.api.OpenTelemetry> otelProvider;

    @Override
    public void afterPropertiesSet() throws Exception {
        log.debug("*******************rpc.enable RpcClientAutoConfigure*******************************");
        // O2 (HARDEN-B2): single Spring binding point for the client default deadline. The apply
        // logic lives in ClientDeadline (shared with rpcurl/generalize); here we only bind config
        // so the two frameworks can't drift (mirrors Batch-1 JwsVerify.bootstrapAndRegister dedup).
        var millis = environment.getProperty(ClientDeadline.CONFIG_KEY, Long.class,
                ClientDeadline.DEFAULT_DEADLINE_MILLIS);
        ClientDeadline.setDefaultDeadlineMillis(millis);
        log.info("[ RPC Client ] default deadline = {} ms ({})", millis,
                millis <= 0 ? "unlimited" : "hung upstream will be cut");
        // OTEL-001: explicitly install the Spring OpenTelemetry into krpc (no global probing).
        if (otelProvider != null) {
            otelProvider.ifAvailable(otel -> {
                tech.krpc.context.KrpcOtel.install(otel);
                log.info("OTel: installed Spring OpenTelemetry into krpc client (tracing active).");
            });
        }
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