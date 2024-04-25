package com.testbt.filter;

import jakarta.enterprise.context.ApplicationScoped;

import tech.krpc.common.FilterChain;
import tech.krpc.filter.GlobalFilter;
import tech.krpc.server.ServerContext;
import tech.krpc.server.ServerFilter;
import tech.krpc.server.ServerResult;
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
