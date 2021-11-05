/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.invoke;

import java.util.Set;
import java.util.stream.Collectors;

import javax.validation.ConstraintViolation;
import javax.validation.Validator;

import com.bt.rpc.model.RpcResult;
import com.bt.rpc.server.ServerContext;
import io.grpc.Status;

/**
 *
 * @author Martin.C
 * @version 2021/11/05 4:04 PM
 */
public abstract class ValInvoke<DTO> implements DynamicInvoke<DTO> {

    final Validator validator;

    final Class inputRowType;

    public ValInvoke(Validator validator, Class inputRowType) {
        this.validator = validator;
        this.inputRowType = inputRowType;
    }

    @Override
    public RpcResult<DTO> invoke(ServerContext sc) throws Throwable {
        var input = DynamicInvoke.parseInput(sc , inputRowType);
        var violationSet = validator.validate(input);
        if(violationSet.size() > 0){
            throw Status.INVALID_ARGUMENT.withDescription(
                    inputRowType.getSimpleName() +" : " + violationSet.stream()
                            .map(it-> it.getPropertyPath()+"="+it.getInvalidValue() +"("+it.getMessage()+")").collect(Collectors.joining(","))
            ).asRuntimeException();
        }
        return methodCall(input);
    }

    public abstract RpcResult<DTO> methodCall(Object input) throws Throwable;
}