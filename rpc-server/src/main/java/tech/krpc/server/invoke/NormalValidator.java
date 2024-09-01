/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.server.invoke;

import jakarta.validation.Validator;

import tech.krpc.server.ServerContext;

/**
 *
 * @author Martin.C
 * @version 2021/11/05 4:04 PM
 */
public class NormalValidator<DTO> extends ValidatorInvoke<DTO> {


    protected final Class inputType;

    public NormalValidator(Validator validator, MethodCall<DTO> caller, Class inputType) {
        super(validator,caller);
        this.inputType = inputType;
    }

    @Override
    public Object readInput(ServerContext sc) {
        return DynamicInvoke.parseInput(sc,inputType);
    }
}