/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package com.bt.rpc.client.spring;

import java.util.Map;
import java.util.Objects;

import com.bt.rpc.client.spring.RpcClientProperties.ClientCfg;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 *
 * @author martin
 * @version 2023/12/12 16:00
 */

//@AutoConfiguration
//@ConditionalOnProperty(name = "rpc.clients.enable")
@Slf4j
//@Component
//@ConfigurationProperties(prefix = "rpc")
//@ConfigurationProperties(prefix = "rpc")
//@EnableConfigurationProperties
public class RpcClientScannerConfigurer implements BeanDefinitionRegistryPostProcessor, InitializingBean, ApplicationContextAware{

    /**
     * rpc
     *   clients:
     *     common-cdn:
     *       url: root
     *       scan: rootpass
     *     xxx:
     *       url: guest
     *       scan: guestpass
     */
    //@Autowired
    //RpcClientConfig clientConfig;

    //Map<String,Object> clients;

    @Setter
    Map<String, ClientCfg> clients;

    //@Override
    //public void setBeanName(String name) {
    //
    //}

    @Override
    public void afterPropertiesSet() throws Exception {
        //log.info("*******************RpcClientScannerConfigurer******************************* ");
        //log.info("*******************RpcClientScannerConfigurer******************************* ");
        //log.info("*******************RpcClientScannerConfigurer******************************* ");
        //log.info("*******************RpcClientScannerConfigurer******************************* ");
        Objects.requireNonNull(this.clients, "clients不能为空");
        //log.info("begin to inject rpc.clients {}", rpcClientProperties);
        log.debug("begin to inject rpc.clients {}", clients);
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        log.debug("begin to postProcessBeanDefinitionRegistry rpc.clients {}", registry);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {

    }
}