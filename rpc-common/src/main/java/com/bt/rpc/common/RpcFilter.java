package com.bt.rpc.common;

/**
 * TODO change this comment
 * 2020-04-03 15:32
 *
 * @author Martin.C
 */
public interface RpcFilter<Res extends ResultWrapper,Ctx extends RpcContext<Res>> {

    Res Invoke(Ctx ctx, FilterChain<Res,Ctx> next) throws Throwable;
}
