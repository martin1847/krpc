package tech.krpc.test.spring.filter;

import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import tech.krpc.common.FilterChain;
import tech.krpc.filter.GlobalFilter;
import tech.krpc.model.RpcResult;
import tech.krpc.server.ServerContext;
import tech.krpc.server.ServerFilter;
import tech.krpc.server.ServerResult;

/**
 * 2020-04-07 14:51
 *
 * @author Martin.C
 */
@GlobalFilter
@Slf4j
@Named
public class ExecServerFilter implements ServerFilter {
    @Override
    public ServerResult Invoke(ServerContext serverContext, FilterChain<ServerResult, ServerContext> next) throws Throwable {

        var s = System.currentTimeMillis();
        try {
            var res = next.invoke(serverContext);
            log.info(" Call method {} cost {} ms ." , serverContext.getMethod() , (System.currentTimeMillis() - s));
            return res;
        }catch (Throwable e){
            //balalalal
            return new ServerResult(500,e.getMessage());
        }
    }
}
