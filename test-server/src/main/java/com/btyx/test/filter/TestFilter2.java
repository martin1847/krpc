package com.btyx.test.filter;

import javax.enterprise.context.ApplicationScoped;

import com.bt.rpc.common.FilterChain;
import com.bt.rpc.server.ServerContext;
import com.bt.rpc.server.ServerFilter;
import com.bt.rpc.server.ServerResult;
import io.quarkus.arc.Unremovable;
import lombok.extern.slf4j.Slf4j;

/**
 * 2020-04-07 14:51
 *
 * @author Martin.C
 */
@ApplicationScoped
@Unremovable
@Slf4j
public class TestFilter2 implements ServerFilter {
    @Override
    public ServerResult Invoke(ServerContext serverContext, FilterChain<ServerResult, ServerContext> next) throws Throwable {

        log.info("I am " + TestFilter2.class.getName());
        var res = next.invoke(serverContext);
        return res;
    }
}
