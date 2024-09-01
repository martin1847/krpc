package tech.krpc.filter;

import tech.krpc.common.FilterChain;
import tech.krpc.common.ResultWrapper;
import tech.krpc.common.RpcContext;

/**
 * 自定义service拦截器，不依赖具体实现Grpc/Dubbo，解藕
 * 2020-04-03 15:32
 *
 * @author Martin.C
 */
public interface RpcFilter<Res extends ResultWrapper,Ctx extends RpcContext<Res>> {

    Res Invoke(Ctx ctx, FilterChain<Res,Ctx> next) throws Throwable;
}
