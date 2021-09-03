package com.bt;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.BeforeBeanDiscovery;
import javax.enterprise.inject.spi.Extension;

import io.smallrye.config.inject.ConfigProducer;

public class CdiExtension implements Extension {



    //protected void beforeBeanDiscovery(@Observes BeforeBeanDiscovery bbd, BeanManager bm) {
    //    AnnotatedType<ConfigProducer> configBean = bm.createAnnotatedType(ConfigProducer.class);
    //    bbd.addAnnotatedType(configBean, ConfigProducer.class.getName());
    //    bbd.add
    //}
    //
    public void afterBean(final @Observes AfterBeanDiscovery afterBeanDiscovery) {
        afterBeanDiscovery
            .addBean()
            .scope(ApplicationScoped.class)
            .types(String.class)
            .id("Created by " + CdiExtension.class)
            .createWith(e -> new String("Hi!"));
    }
}
