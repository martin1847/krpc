package com.bt.demo.service;

import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.model.RpcResult;

/**
 * 2020-01-02 16:19
 *
 * @author Martin.C
 */
@RpcService
public interface HelloService {
//    @GrpcMethod
    public RpcResult<HelloRes> hello(HelloBean request1, HelloBean request2);


    public RpcResult<String> hello2();
}
