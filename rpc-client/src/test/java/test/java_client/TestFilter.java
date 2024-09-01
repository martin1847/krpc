package test.java_client;

import tech.krpc.client.ClientContext;
import tech.krpc.client.ClientFilter;
import tech.krpc.client.ClientResult;
import tech.krpc.common.FilterChain;

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
