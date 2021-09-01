package com.bt.rpc.demo.filter;

import com.bt.rpc.client.ClientContext;
import com.bt.rpc.client.ClientFilter;
import com.bt.rpc.client.ClientResult;
import com.bt.rpc.common.FilterChain;

/**
 * 2020-04-03 17:19
 *
 * @author Martin.C
 */
public class TestFilter implements ClientFilter {
    @Override
    public ClientResult Invoke(ClientContext clientContext, FilterChain<ClientResult, ClientContext> next) throws Throwable {
        var s = System.currentTimeMillis();
        var res = next.invoke(clientContext);
        System.out.println(" Call RPC cost " + clientContext.getMethod() +"  " + (System.currentTimeMillis() - s) +"ms");
        return res;
    }
}
