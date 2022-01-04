package com.btyx.test.impl;

import com.bt.rpc.server.ServerContext;
import com.btyx.test.dto.TimeReq;
import com.btyx.test.dto.TimeResult;
import com.btyx.test.DemoService;
import com.bt.rpc.model.RpcResult;
import com.bt.rpc.util.EnvUtils;
import com.btyx.test.dto.User;
import com.btyx.test.dto.UserStatus;
import io.quarkus.runtime.Startup;

import javax.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.time.temporal.ChronoField;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * TODO change this comment
 * 2020-01-06 15:52
 *
 * @author Martin.C
 */
@ApplicationScoped
@Startup
public class DemoServiceImpl implements DemoService {
    @Override
    public RpcResult<TimeResult> hello(TimeReq req) {
        var res = new TimeResult();

        res.setTime(" from  (" + EnvUtils.hostName() + ", with meta : " + ServerContext.current().getHeaders()+") : " + req);
        res.setTimestamp(System.currentTimeMillis());
        return RpcResult.ok(res);
    }

    @Override
    public RpcResult<String> save(User user) {
        return RpcResult.ok(String.valueOf(user));
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
    public RpcResult<String> str(String in) {
        return RpcResult.ok("java5678:got:" + in);
    }

    @Override
    public RpcResult<Map<String, Integer>> testMap() {
        return RpcResult.ok(Collections.singletonMap("key1",123));
    }

    @Override
    public RpcResult<Integer> inc100(Integer i) {
        return RpcResult.ok( 100 + i);
    }

    @Override
    public RpcResult<Integer> testRuntimeException() {
        throw  new RuntimeException("Test Java RuntimeException");
        //return RpcResult.success(9527);
    }

    @Override
    public RpcResult<List<Integer>> wordLength(List<String> list) {
        return
                RpcResult.ok(list.stream().map(String::length).collect(Collectors.toList()));
    }
}
