package com.bt.rpc;

import com.bt.rpc.common.meta.*;
import com.bt.rpc.model.RpcResult;
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