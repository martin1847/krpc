//package com.bt;
//
//import com.bt.demo.TimeReq;
//import com.bt.demo.TimeResult;
//import com.bt.demo.TimeService;
//import com.bt.demo.service.HelloService;
//import io.quarkus.runtime.annotations.RegisterForReflection;
//
//@RegisterForReflection(targets = {
//        TimeReq.class,
//        TimeResult.class,
//        HelloService.class,
//        TimeService.class
//
//})
//public class MyReflectionConfiguration {
//
//
//    //https://quarkus.io/guides/writing-native-applications-tips#managing-proxy-classes-2
////    @BuildStep
////    NativeImageProxyDefinitionBuildItem httpProxies() {
////        return new NativeImageProxyDefinitionBuildItem("org.apache.http.conn.HttpClientConnectionManager",
////                "org.apache.http.pool.ConnPoolControl", "com.amazonaws.http.conn.Wrapped");
////    }
//}