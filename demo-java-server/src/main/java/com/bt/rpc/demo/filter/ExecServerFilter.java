package com.bt.rpc.demo.filter;

import com.bt.rpc.server.ServerContext;
import com.bt.rpc.server.ServerFilter;
import com.bt.rpc.server.ServerResult;
import com.bt.rpc.common.FilterChain;

import javax.inject.Singleton;

/**
 * 2020-04-07 14:51
 *
 * @author Martin.C
 */
@Singleton
public class ExecServerFilter implements ServerFilter {
    @Override
    public ServerResult Invoke(ServerContext serverContext, FilterChain<ServerResult, ServerContext> next) throws Throwable {

        var s = System.currentTimeMillis();
        var res = next.invoke(serverContext);
        System.out.println(" Call RPC cost " + serverContext.getMethod() +"  " + (System.currentTimeMillis() - s) +"ms");
        return res;
    }
}
