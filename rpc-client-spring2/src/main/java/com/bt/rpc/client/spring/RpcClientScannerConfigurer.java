/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package com.bt.rpc.client.spring;

import java.beans.Introspector;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.client.CacheManager;
import com.bt.rpc.client.RpcClientFactory;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import io.grpc.ManagedChannelBuilder;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.util.ClassUtils;

/**
 * @author martin
 * @version 2023/12/12 16:00
 */

@Slf4j
public class RpcClientScannerConfigurer implements BeanDefinitionRegistryPostProcessor, InitializingBean {
    //, ApplicationContextAware{

    /**
     * rpc
     * clients:
     * common-cdn:
     * url: root
     * scan: rootpass
     * xxx:
     * url: guest
     * scan: guestpass
     */
    //@Autowired
    //RpcClientConfig clientConfig;

    //Map<String,Object> clients;

    @Setter
    //@Autowired
            Map<String, RpcCfg> clients;

    @Setter
    @Autowired
    CacheManager cacheManager;

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
        log.debug("begin to inject rpc.clients {} / {}", cacheManager, clients);
    }

    @Override
    @SneakyThrows
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {

        //var scanner = new ClassPathBeanDefinitionScanner(registry);

        for (var kv : clients.entrySet()) {

            var appName = kv.getKey();
            var cfg = kv.getValue();

            var pkgs = cfg.scan.split(",");

            var clzSet = new HashSet<Class>();
            for (var pkg : pkgs) {
                ClassPath.from(ClassUtils.getDefaultClassLoader())
                        .getAllClasses()
                        .stream()
                        .filter(ci -> ci.isTopLevel() && ci.getName().startsWith(pkg))
                        .map(ClassInfo::load)
                        .filter(it -> it.isInterface() && it.isAnnotationPresent(RpcService.class))
                        .forEach(clzSet::add);

            }
            if (clzSet.isEmpty()) {
                log.warn("found 0 RpcService for {}/{} , skip !!! ", appName, cfg);
                continue;
            }

            var url = new URL(cfg.getUrl());

            var channelBuilder =
                    ManagedChannelBuilder.forAddress(url.getHost(), url.getPort());
            if ("https".equals(url.getProtocol())) {
                channelBuilder.useTransportSecurity();
            } else {
                channelBuilder.usePlaintext();
            }

            var fac = new RpcClientFactory(appName, channelBuilder.build());
            fac.setCacheManager(cacheManager);
            log.info("build RpcClientFactory {}/{}", appName, fac);

            for (var clz : clzSet) {
                var bd = new RootBeanDefinition(clz, () -> fac.get(clz));
                //AnnotationBeanNameGenerator.buildDefaultBeanName
                var name = Introspector.decapitalize(clz.getSimpleName());
                registry.registerBeanDefinition(name, bd);
                log.info("registry Rpc Client bean  {} -> {}", name, clz);
            }
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

    }
    //
    //@Override
    //public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
    //
    //}
}