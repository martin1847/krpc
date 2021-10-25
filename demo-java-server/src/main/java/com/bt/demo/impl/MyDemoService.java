package com.bt.demo.impl;

import com.bt.demo.TimeReq;
import com.bt.demo.TimeResult;
import com.bt.demo.DemoService;
import com.bt.rpc.model.RpcResult;
import com.bt.rpc.util.EnvUtils;
import io.quarkus.runtime.Startup;

import javax.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.time.temporal.ChronoField;
import java.util.Collections;
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
public class MyDemoService implements DemoService {
    @Override
    public RpcResult<TimeResult> hello(TimeReq req) {
        var res = new TimeResult();
        res.setTime(" from  (" + EnvUtils.hostName() + ") : " + req);
        res.setTimestamp(System.currentTimeMillis());
        return RpcResult.ok(res);
    }

    @Override
    public RpcResult<byte[]> bytesTime() {
        var now = LocalDateTime.now();
        return RpcResult.ok(new byte[]{
                (byte) ( now.get(ChronoField.YEAR_OF_ERA) -2000 ),
                (byte) now.get(ChronoField.MONTH_OF_YEAR),
                (byte) now.get(ChronoField.DAY_OF_MONTH),
                (byte) now.get(ChronoField.HOUR_OF_DAY),
                (byte) now.get(ChronoField.MINUTE_OF_HOUR),
                (byte) now.get(ChronoField.SECOND_OF_MINUTE)
        });
    }

    @Override
    public RpcResult<byte[]> incBytes(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (bytes[i]+1);
        }
        return RpcResult.ok(bytes);
    }

    @Override
    public RpcResult<Integer> bytesSum(byte[] bytes) {
        int sum = bytes[0];
        for (int i = 1; i < bytes.length; i++) {
            sum += bytes[i];
        }
        return RpcResult.ok(sum);
    }

    @Override
    public RpcResult<String> str() {
        return RpcResult.ok("java5678");
    }

    @Override
    public RpcResult<Map<String, Integer>> map() {
        return RpcResult.ok(Collections.singletonMap("key1",123));
    }

    @Override
    public RpcResult<Integer> pingWithRuntimeException() {
        throw  new RuntimeException("Test Java RuntimeException");
       //return RpcResult.success(9527);
    }

    @Override
    public RpcResult<List<Integer>> list() {
        return RpcResult.ok(List.of(123,456));
    }

}
