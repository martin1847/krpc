/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.invoke;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

import javax.validation.Validator;

import com.bt.rpc.model.RpcResult;

/**
 *
 * @author Martin.C
 * @version 2021/11/05 4:38 PM
 */
public class MhVal extends ValInvoke{

    final MethodHandle mh;

    public MhVal(Validator validator, Class inputRowType, MethodHandle mh) {
        super(validator, inputRowType);
        this.mh = mh;
    }

    @Override
    public RpcResult methodCall(Object input) throws Throwable {
        return (RpcResult) mh.invoke(input);
    }
}