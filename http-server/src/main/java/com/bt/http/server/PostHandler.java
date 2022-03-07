/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.http.server;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 *
 * @author Martin.C
 * @version 2021/12/22 9:24 AM
 */
public abstract class PostHandler<ParamDTO> implements Handler<ParamDTO> {

    public final Class<ParamDTO> paramClass;

    public final boolean useValidator ;

    public PostHandler(){
        this(true);
    }
    public PostHandler(boolean useValidator) {
        Type[] pTypes = getParameterizedTypes(this);
        paramClass = (Class<ParamDTO>) pTypes[0];
        this.useValidator = useValidator;
    }

    public static Type[] getParameterizedTypes(Object object) {
        Type superclassType = object.getClass().getGenericSuperclass();
        if (!ParameterizedType.class.isAssignableFrom(superclassType.getClass())) {
            return null;
        }
        return ((ParameterizedType) superclassType).getActualTypeArguments();
    }
}