package com.bt.demo;

import com.bt.demo.dto.TimeReq;
import com.bt.demo.dto.TimeResult;
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
public interface DemoService {

//    @Cached
    RpcResult<TimeResult> hello(TimeReq req);

    RpcResult<byte[]> bytesTime();

    RpcResult<byte[]> incBytes(byte[] bytes);

    RpcResult<Integer> bytesSum(byte[] bytes);

    @Deprecated
//    @Cached
    RpcResult<String> str();
    RpcResult<Map<String,Integer>> map();

    RpcResult<Integer> pingWithRuntimeException();

    RpcResult<List<Integer>> list();
}
