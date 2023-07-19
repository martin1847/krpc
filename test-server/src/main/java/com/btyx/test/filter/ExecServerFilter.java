package com.btyx.test.filter;

import jakarta.enterprise.context.ApplicationScoped;

import com.bt.rpc.common.FilterChain;
import com.bt.rpc.filter.GlobalFilter;
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
@GlobalFilter
@Unremovable
@Slf4j
public class ExecServerFilter implements ServerFilter {
    @Override
    public ServerResult Invoke(ServerContext serverContext, FilterChain<ServerResult, ServerContext> next) throws Throwable {

        var s = System.currentTimeMillis();
        var res = next.invoke(serverContext);
        log.info(" Call method {} cost {} ms ." , serverContext.getMethod() , (System.currentTimeMillis() - s));
        return res;
    }
}
