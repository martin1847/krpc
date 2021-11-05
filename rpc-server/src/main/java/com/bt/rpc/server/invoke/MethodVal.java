/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.invoke;

import java.lang.reflect.Method;

import javax.validation.Validator;

import com.bt.rpc.model.RpcResult;

/**
 *
 * @author Martin.C
 * @version 2021/11/05 4:38 PM
 */
public class MethodVal extends ValInvoke{

    final Object serviceToInvoke;

    final Method method;
    //public MethodVal(Validator validator, Class inputRowType) {
    //    super(validator, inputRowType);
    //}

    public MethodVal(Validator validator, Class inputRowType, Object serviceToInvoke, Method method) {
        super(validator, inputRowType);
        this.serviceToInvoke = serviceToInvoke;
        this.method = method;
    }

    @Override
    public RpcResult methodCall(Object input) throws Exception {
        return (RpcResult) method.invoke(serviceToInvoke,input);
    }
}