/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.server.invoke;

import java.util.stream.Collectors;

import jakarta.validation.Validator;

import tech.krpc.model.RpcResult;
import tech.krpc.server.ServerContext;
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
        var violationSet = validator.validate(input);
        if (!violationSet.isEmpty()) {
            // AGENT-002 F1: carry typed {field, constraint} pairs only. The rejected value
            // (getInvalidValue()) is deliberately NEVER read — it may be a secret (password/
            // token). ValidationException encodes field+constraint into BOTH the typed carrier
            // (MCP face) and the status description (classic gRPC face), never the value.
            var violations = violationSet.stream()
                    .map(it -> new ValidationException.Violation(
                            it.getPropertyPath().toString(), it.getMessage()))
                    .collect(Collectors.toList());
            throw new ValidationException(input.getClass().getSimpleName(), violations);
        }
        return caller.call(input);
    }

    public abstract Object readInput(ServerContext sc);

}