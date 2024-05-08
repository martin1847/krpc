package tech.krpc;

import tech.krpc.model.RpcResult;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(targets={
        RpcResult.class,
        Anno.class,
        Api.class,
        ApiMeta.class,
        Dto.class,
        Method.class,
        Property.class})
public class MyReflectionConfiguration {
}