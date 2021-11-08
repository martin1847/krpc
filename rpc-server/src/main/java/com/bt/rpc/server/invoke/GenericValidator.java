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
public class GenericValidator<DTO> extends ValidatorInvoke<DTO> {


    final ParameterizedType genericType;


    public GenericValidator(Validator validator, MethodCall<DTO> caller, ParameterizedType genericType) {
        super(validator,caller);
        this.genericType = genericType;
    }

    @Override
    public Object readInput(ServerContext sc) {
        return DynamicInvoke.parseInput(sc,genericType);
    }
}