package com.bt.rpc.server.invoke;

import java.lang.reflect.ParameterizedType;

import com.bt.rpc.model.RpcResult;
import com.bt.rpc.serial.Serial;
import com.bt.rpc.server.ServerContext;

@FunctionalInterface
public interface DynamicInvoke<DTO> {

    RpcResult<DTO> invoke(ServerContext sc) throws Throwable;

    static Object parseInput(ServerContext sc, Class inputType) {
        return Serial.Instance.get(sc.getArg().getEValue()).readInput(sc.getArg(), inputType);
    }

    static Object parseInput(ServerContext sc, ParameterizedType inputType) {
        return Serial.Instance.get(sc.getArg().getEValue()).readInput(sc.getArg(), inputType);
    }
}