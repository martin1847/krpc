package com.testbt.impl;

import jakarta.enterprise.context.ApplicationScoped;

import com.bt.rpc.model.RpcResult;
import com.bt.rpc.server.ServerContext;
import com.bt.rpc.util.EnvUtils;
import com.testbt.AbstractDemoService;
import com.testbt.dto.TimeReq;
import com.testbt.dto.TimeResult;
import io.quarkus.runtime.Startup;

/**
 * 2020-01-06 15:52
 *
 * @author Martin.C
 */
@ApplicationScoped
@Startup
public class DemoServiceImpl extends AbstractDemoService {
    @Override
    public RpcResult<TimeResult> hello(TimeReq req) {
        var res = new TimeResult();

        res.setTime(" from  (" + EnvUtils.hostName() + ", with meta : " + ServerContext.current().getHeaders()+") : " + req);
        res.setTimestamp(System.currentTimeMillis());
        return RpcResult.ok(res);
    }

}
