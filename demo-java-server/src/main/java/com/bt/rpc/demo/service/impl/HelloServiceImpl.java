package com.bt.rpc.demo.service.impl;

import com.bt.demo.service.HelloBean;
import com.bt.demo.service.HelloRes;
import com.bt.demo.service.HelloService;
import com.bt.rpc.model.RpcResult;
import io.quarkus.runtime.Startup;

import javax.enterprise.context.ApplicationScoped;
import java.util.Date;

/**
 * TODO change this comment
 * 2020-01-02 16:21
 *
 * @author Martin.C
 */
@ApplicationScoped
@Startup
public class HelloServiceImpl implements HelloService {

    @Override
    public RpcResult<HelloRes> hello(HelloBean request1, HelloBean request2) {
        return RpcResult.success(new HelloRes(new Date().toString() +" \t " + request1 +"\t" + request2));
    }

    @Override
    public RpcResult<String> hello2() {
        return RpcResult.success(System.currentTimeMillis() + "");
    }
}
