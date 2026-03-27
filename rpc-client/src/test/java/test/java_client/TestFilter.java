package test.java_client;

import java.util.concurrent.TimeUnit;

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

    //static final Map<Class, CallOptions> classCallOptionsMap = new HashMap<>();
    //static {
    //    classCallOptionsMap.put(SomeRpc.class,CallOptions.DEFAULT.withDeadlineAfter(500,TimeUnit.MICROSECONDS));
    //}
    @Override
    public ClientResult Invoke(ClientContext clientContext, FilterChain<ClientResult, ClientContext> next) throws Throwable {

        var opt = clientContext.getCallOptions().withDeadlineAfter(500, TimeUnit.MICROSECONDS);
        //由于CallOptions被设置为Immutable，修改后要重复赋值才生效
        clientContext.setCallOptions(opt);

        //也可以根据service或者method级别，统一配置一个全局map，去设置不同的超时时间。
        //classCallOptionsMap.get(clientContext.getClass())
        clientContext.getClass();
        clientContext.getMethod();

        var s = System.currentTimeMillis();
        var res = next.invoke(clientContext);
        System.out.println(" Call RPC cost " + clientContext.getMethod() +"  " + (System.currentTimeMillis() - s) +"ms");
        return res;
    }
}
