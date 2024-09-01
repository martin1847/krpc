/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.server.invoke;

import java.util.stream.Collectors;

import jakarta.validation.Validator;

import tech.krpc.model.RpcResult;
import tech.krpc.server.ServerContext;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Martin.C
 * @version 2021/11/08 2:02 PM
 */
@Slf4j
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
        //TODO log.debug("ValidatorInvoke get  PageQuery, no validator  input {}",input);
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