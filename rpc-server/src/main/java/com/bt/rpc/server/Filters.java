/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server;

/**
 *
 * @author Martin.C
 * @version 2021/11/05 1:43 PM
 */

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Filters {

    Class<? extends ServerFilter>[] value();

    //boolean autoCreate() default true;

    boolean ignoreNotFound() default false;
}