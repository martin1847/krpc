package com.bt.demo.impl;

import com.bt.demo.TimeReq;
import com.bt.demo.TimeResult;
import com.bt.demo.TimeService;
import com.bt.rpc.model.RpcResult;
import io.quarkus.runtime.Startup;

import javax.enterprise.context.ApplicationScoped;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * TODO change this comment
 * 2020-01-06 15:52
 *
 * @author Martin.C
 */
@ApplicationScoped
@Startup
public class MyTimeService implements TimeService {
    @Override
    public RpcResult<TimeResult> hello(TimeReq req) {
        var res = new TimeResult();
        res.setTime( new Date()+ " \t Java   " + getClass().getName() + " : " + req);
        res.setTimestamp(System.currentTimeMillis());
        return RpcResult.ok(res);
    }

    @Override
    public RpcResult<byte[]> bytes() {
        return RpcResult.ok("9527".getBytes());
    }

    @Override
    public RpcResult<byte[]> incBytes(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (bytes[i]+1);
        }
        return RpcResult.ok(bytes);
    }

    @Override
    public RpcResult<String> ping2() {
        return RpcResult.ok("java5678");
    }

    @Override
    public RpcResult<Map<String, Integer>> ping1() {
        return RpcResult.ok(Collections.singletonMap("mappppa",123));
    }

    @Override
    public RpcResult<Integer> pingWithRuntimeException() {
        throw  new RuntimeException("Test Java RuntimeException");
       //return RpcResult.success(9527);
    }

    @Override
    public RpcResult<List<Integer>> ping3() {
        return RpcResult.ok(List.of(123,456));
    }
}
