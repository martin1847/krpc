package tech.krpc.test.spring.service;


import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import tech.krpc.model.RpcResult;
import tech.krpc.server.Filters;
import tech.krpc.server.ServerContext;
import tech.krpc.test.spring.filter.TestFilter;
import tech.krpc.util.EnvUtils;
import tech.test.krpc.AbstractDemoService;
import tech.test.krpc.dto.TimeReq;
import tech.test.krpc.dto.TimeResult;
import tech.test.krpc.inner.DemoRpc;

/**
 * 2020-01-06 15:52
 *
 * @author Martin.C
 */
@Named
@Slf4j
@Filters(TestFilter.class)
public class DemoServiceImpl extends AbstractDemoService {


    @Inject
    DemoRpc demoRpc;

    @Override
    public RpcResult<String> str(String in) {
        log.info("demoRpc: {}" , demoRpc);
        return RpcResult.ok("spring5678:got: [ " + in +" ] , headers : "  + ServerContext.current().getHeaders().toString());
    }


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
