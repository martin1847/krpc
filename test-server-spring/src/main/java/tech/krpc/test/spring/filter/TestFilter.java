package tech.krpc.test.spring.filter;

import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import tech.krpc.common.FilterChain;
import tech.krpc.server.ServerContext;
import tech.krpc.server.ServerFilter;
import tech.krpc.server.ServerResult;

/**
 * 2020-04-07 14:51
 *
 * @author Martin.C
 */
@Slf4j
@Named
public class TestFilter implements ServerFilter {
    @Override
    public ServerResult Invoke(ServerContext serverContext, FilterChain<ServerResult, ServerContext> next) throws Throwable {

        log.info("I am " + TestFilter.class.getName());
        var res = next.invoke(serverContext);
        return res;
    }
}
