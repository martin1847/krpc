///**
// * Martin.Cong
// * Copyright (c) 2021-2021 All Rights Reserved.
// */
//package com.btyx.test.impl;
//
//import java.io.IOException;
//import java.net.URISyntaxException;
//
//import jakarta.enterprise.context.ApplicationScoped;
//import jakarta.enterprise.event.Observes;
//import jakarta.inject.Inject;
//import jakarta.validation.Validator;
//
//import io.quarkus.runtime.StartupEvent;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.ibatis.session.SqlSessionFactory;
//
///**
// *
// * @author Martin.C
// * @version 2021/10/27 2:18 PM
// */
//@ApplicationScoped
//@Slf4j
//public class InitMybatis {
//
//    @Inject// by the ext
//    SqlSessionFactory sqlSessionFactory;
//
//    @Inject
//    Validator validator;
//
//    void onStart(@Observes StartupEvent ev) throws IOException, URISyntaxException {
//        log.info("The application is starting  InitMybatis ..." + sqlSessionFactory);
//        log.info("The application is starting  validator ..." + validator);
//
//
//    }
//
//}