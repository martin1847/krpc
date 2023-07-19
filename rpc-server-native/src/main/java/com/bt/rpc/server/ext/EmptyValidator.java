/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.bt.rpc.server.ext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.validation.executable.ExecutableValidator;
import javax.validation.metadata.BeanDescriptor;

import io.quarkus.arc.DefaultBean;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Martin.C
 * @version 2022/02/11 4:56 PM
 */
@ApplicationScoped
@DefaultBean
@Slf4j
@Named("EmptyValidator")
public class EmptyValidator implements Validator {
    @Override
    public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
        log.warn("use EmptyValidator.validate");
        return Collections.emptySet();
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, Class<?>... groups) {
        log.warn("use EmptyValidator.validateProperty");
        return Collections.emptySet();
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateValue(Class<T> beanType, String propertyName, Object value, Class<?>... groups) {
        log.warn("use EmptyValidator.validateValue");
        return Collections.emptySet();
    }

    @Override
    public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
        return null;
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        return null;
    }

    @Override
    public ExecutableValidator forExecutables() {
        log.warn("use EmptyValidator.forExecutables");
        return new ExecutableValidator() {
            @Override
            public <T> Set<ConstraintViolation<T>> validateParameters(T object, Method method, Object[] parameterValues,
                                                                      Class<?>... groups) {
                return Collections.emptySet();
            }

            @Override
            public <T> Set<ConstraintViolation<T>> validateReturnValue(T object, Method method, Object returnValue, Class<?>... groups) {
                return Collections.emptySet();
            }

            @Override
            public <T> Set<ConstraintViolation<T>> validateConstructorParameters(Constructor<? extends T> constructor,
                                                                                 Object[] parameterValues, Class<?>... groups) {
                return Collections.emptySet();
            }

            @Override
            public <T> Set<ConstraintViolation<T>> validateConstructorReturnValue(Constructor<? extends T> constructor, T createdObject,
                                                                                  Class<?>... groups) {
                return Collections.emptySet();
            }
        };
    }
}