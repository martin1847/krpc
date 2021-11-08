/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.invoke;

import java.util.stream.Collectors;

import javax.validation.Validator;

import com.bt.rpc.model.RpcResult;
import com.bt.rpc.server.ServerContext;
import io.grpc.Status;

/**
 *
 * @author Martin.C
 * @version 2021/11/08 2:02 PM
 */
public abstract class ValidatorInvoke<DTO>  implements DynamicInvoke<DTO>{
    protected final Validator validator;

    protected final MethodCall<DTO> caller;

    public ValidatorInvoke(Validator validator,MethodCall<DTO> caller) {
        this.validator = validator;
        this.caller = caller;
    }
    @Override
    public RpcResult<DTO> invoke(ServerContext sc) throws Throwable {
        var input = readInput(sc);
        var violationSet = validator.validate(input);
        if(violationSet.size() > 0){
            throw Status.INVALID_ARGUMENT.withDescription(
                    input.getClass().getSimpleName() +" : " + violationSet.stream()
                            .map(it-> it.getPropertyPath()+"="+it.getInvalidValue() +"("+it.getMessage()+")")
                            .collect(Collectors.joining(";"))
            ).asRuntimeException();
        }
        return caller.call(input);
    }

    public abstract Object readInput(ServerContext sc);

}