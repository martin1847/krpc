package com.bt.rpc.common;

/**
 * 2020-04-03 15:24
 *
 * @author Martin.C
 */
@FunctionalInterface
public interface FilterChain<DTO,Ctx extends RpcContext<DTO>> {


    DTO invoke(Ctx context) throws  Throwable;

}
