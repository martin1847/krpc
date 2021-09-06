//package com.bt;
//
//import javax.annotation.PreDestroy;
//import javax.enterprise.context.ApplicationScoped;
//import javax.enterprise.event.Observes;
//import javax.inject.Inject;
//
//import io.quarkus.runtime.ShutdownEvent;
//import io.quarkus.runtime.StartupEvent;
//import lombok.extern.slf4j.Slf4j;
//
//@ApplicationScoped
//@Slf4j
////@Startup
//public class App {
//
//
//    @Inject
//    RpcConfig rpcConfig;
//
//    RpcServiceExpose rpcServiceExpose;
//
//    //void onStart(@Observes StartupEvent ev) {
//    //    //LOGGER.info("The application is starting...");
//    //}
//
//    void onStop(@Observes ShutdownEvent ev) {
//        //LOGGER.info("The application is stopping...");
//    }
//
//    //@PostConstruct
//    public void onStart(@Observes StartupEvent ev)  throws Exception {
//        log.info("The application is starting...");
//        rpcServiceExpose = new RpcServiceExpose();
//        rpcServiceExpose.expose(rpcConfig);
//    }
//
//    @PreDestroy
//    public void onStop() {
//        rpcServiceExpose.shutdown();
//    }
//}