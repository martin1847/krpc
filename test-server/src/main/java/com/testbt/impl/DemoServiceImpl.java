package com.testbt.impl;

import jakarta.enterprise.context.ApplicationScoped;

import tech.krpc.model.RpcResult;
import com.bt.rpc.server.ServerContext;
import tech.krpc.util.EnvUtils;
import com.testbt.AbstractDemoService;
import com.testbt.dto.TimeReq;
import com.testbt.dto.TimeResult;
import io.quarkus.runtime.Startup;
import lombok.extern.slf4j.Slf4j;

/**
 * 2020-01-06 15:52
 *
 * @author Martin.C
 */
@ApplicationScoped
@Startup
@Slf4j
public class DemoServiceImpl extends AbstractDemoService {
    @Override
    public RpcResult<TimeResult> hello(TimeReq req) {


        log.debug("get request {}",req);

        var res = new TimeResult();

        res.setTime(" from  (" + EnvUtils.hostName() + ", with meta : " + ServerContext.current().getHeaders()+") : " + req);
        res.setTimestamp(System.currentTimeMillis());
        return RpcResult.ok(res);
    }

    @Override
    public RpcResult<Integer> testLogicError(Integer i) {
        return RpcResult.error(666,"测试逻辑错误～～～！！！666！！！！");
    }

}
