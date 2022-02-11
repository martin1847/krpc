//package com.bt.rpc.server.ext;///**
//
//import java.util.Set;
//
//import javax.enterprise.context.ApplicationScoped;
//import javax.enterprise.event.Observes;
//import javax.inject.Inject;
//import javax.validation.ConstraintViolation;
//import javax.validation.Validator;
//import javax.validation.executable.ExecutableValidator;
//import javax.validation.metadata.BeanDescriptor;
//
//import io.quarkus.arc.DefaultBean;
//import io.quarkus.runtime.StartupEvent;
//import lombok.extern.slf4j.Slf4j;
//
///**
// *
// * @author Martin.C
// * @version 2021/10/27 2:18 PM
// */
//@ApplicationScoped
//@Slf4j
//public class ActiveValidator {
//
//    //@Inject
//    Validator validator;
//
//
//    @DefaultBean
//    class EmptyValidator implements Validator {
//        @Override
//        public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
//            return null;
//        }
//
//        @Override
//        public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, Class<?>... groups) {
//            return null;
//        }
//
//        @Override
//        public <T> Set<ConstraintViolation<T>> validateValue(Class<T> beanType, String propertyName, Object value, Class<?>... groups) {
//            return null;
//        }
//
//        @Override
//        public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
//            return null;
//        }
//
//        @Override
//        public <T> T unwrap(Class<T> type) {
//            return null;
//        }
//
//        @Override
//        public ExecutableValidator forExecutables() {
//            return null;
//        }
//    }
//
//    void onStart(@Observes StartupEvent ev) {
//        log.debug("Use validator {}", validator);
//    }
//
//}