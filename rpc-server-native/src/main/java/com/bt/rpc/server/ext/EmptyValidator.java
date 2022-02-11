///**
// * Botaoyx.com Inc.
// * Copyright (c) 2021-2022 All Rights Reserved.
// */
//package com.bt.rpc.server.ext;
//
//import java.util.Set;
//
//import javax.validation.ConstraintViolation;
//import javax.validation.Validator;
//import javax.validation.executable.ExecutableValidator;
//import javax.validation.metadata.BeanDescriptor;
//
//import io.quarkus.arc.DefaultBean;
//
///**
// *
// * @author Martin.C
// * @version 2022/02/11 4:56 PM
// */
//@DefaultBean
//public class EmptyValidator implements Validator {
//    @Override
//    public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
//        return null;
//    }
//
//    @Override
//    public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, Class<?>... groups) {
//        return null;
//    }
//
//    @Override
//    public <T> Set<ConstraintViolation<T>> validateValue(Class<T> beanType, String propertyName, Object value, Class<?>... groups) {
//        return null;
//    }
//
//    @Override
//    public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
//        return null;
//    }
//
//    @Override
//    public <T> T unwrap(Class<T> type) {
//        return null;
//    }
//
//    @Override
//    public ExecutableValidator forExecutables() {
//        return null;
//    }
//}