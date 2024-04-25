package com.bt.rpc.common;

import tech.krpc.annotation.RpcService;
import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.model.RpcResult;

/**
 * 2020-01-19 16:05
 *
 * @author Martin.C
 */
@RpcService
@UnsafeWeb
public interface MService {
    /**
     * 返回GraalVM 构建号
     */
    RpcResult<String> v();

    /**
     * 返回 req headers
     */
    RpcResult<String> h();

}
