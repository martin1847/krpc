package tech.krpc.server.invoke;

import java.lang.reflect.ParameterizedType;

import tech.krpc.model.RpcResult;
import tech.krpc.serial.Serial;
import tech.krpc.server.ServerContext;

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