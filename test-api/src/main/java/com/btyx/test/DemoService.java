package com.btyx.test;

import com.bt.rpc.annotation.UnsafeWeb;
import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.model.RpcResult;
import com.btyx.test.dto.TimeReq;
import com.btyx.test.dto.TimeResult;

import java.util.List;
import java.util.Map;

/**
 * 2020-01-06 15:51
 *
 * @author Martin.C
 */
@UnsafeWeb
@RpcService(description = "this is a Java test service")
public interface DemoService {

    //    @Cached
    RpcResult<TimeResult> hello(TimeReq req);

    RpcResult<byte[]> bytesTime();

    RpcResult<byte[]> incBytes(byte[] bytes);

    RpcResult<Integer> bytesSum(byte[] bytes);

    @Deprecated
    RpcResult<String> str(String hello);

    /**
     * Only for test . use Map is a not a good design for really service
     */
    RpcResult<Map<String,Integer>> testMap();

    RpcResult<Integer> inc100(Integer i);

    RpcResult<Integer> testRuntimeException();

    RpcResult<List<Integer>> wordLength(List<String> list);
}
