package com.bt.rpc.common;

import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.annotation.UnsafeWeb;
import com.bt.rpc.model.RpcResult;

/**
 * 2020-01-19 16:05
 *
 * @author Martin.C
 */
@RpcService
@UnsafeWeb
public interface _MetaService {
    /**
     * 返回GraalVM 构建号
     */
    RpcResult<String> v();

    /**
     * 返回 req headers
     */
    RpcResult<String> h();

}
