package com.bt.rpc.filter;

import com.bt.rpc.common.FilterChain;
import com.bt.rpc.common.ResultWrapper;
import com.bt.rpc.common.RpcContext;

/**
 * TODO change this comment
 * 2020-04-03 15:32
 *
 * @author Martin.C
 */
public interface RpcFilter<Res extends ResultWrapper,Ctx extends RpcContext<Res>> {

    Res Invoke(Ctx ctx, FilterChain<Res,Ctx> next) throws Throwable;
}
