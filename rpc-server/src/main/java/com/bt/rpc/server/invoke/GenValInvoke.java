/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.invoke;

import java.lang.reflect.ParameterizedType;

import javax.validation.Validator;

import com.bt.rpc.model.RpcResult;
import com.bt.rpc.serial.Serial;
import com.bt.rpc.server.ServerContext;

/**
 *
 * @author Martin.C
 * @version 2021/11/05 4:04 PM
 */
public abstract class GenValInvoke<DTO> implements DynamicInvoke<DTO> {

    final Validator validator;

    final ParameterizedType inputRowType;

    public GenValInvoke(Validator validator, ParameterizedType inputRowType) {
        this.validator = validator;
        this.inputRowType = inputRowType;
    }

    @Override
    public RpcResult<DTO> invoke(ServerContext sc) throws Throwable {
        var input = DynamicInvoke.parseInput(sc, inputRowType);
        validator.validate(input);
        return methodCall(input);
    }

    public abstract RpcResult<DTO> methodCall(Object input) throws Throwable;
}