package com.bt.demo;

import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.model.RpcResult;

import java.util.List;
import java.util.Map;

/**
 * 2020-01-06 15:51
 *
 * @author Martin.C
 */
@RpcService(description = "this is a Java test service")
public interface TimeService {

//    @Cached
    RpcResult<TimeResult> hello(TimeReq req);

    RpcResult<byte[]> bytes();

    RpcResult<byte[]> incBytes(byte[] bytes);

    @Deprecated
//    @Cached
    RpcResult<String> ping2();
    RpcResult<Map<String,Integer>> ping1();

    RpcResult<Integer> pingWithRuntimeException();

    RpcResult<List<Integer>> ping3();
}
