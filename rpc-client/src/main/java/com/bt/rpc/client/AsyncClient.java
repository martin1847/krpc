/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.bt.rpc.client;

import java.util.HashMap;
import java.util.Map;

import com.bt.rpc.client.AsyncMethod.ResultObserver;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Martin.C
 * @version 2022/01/07 2:06 PM
 */
@Slf4j
public class AsyncClient<RpcService> {

    final Map<String, AsyncMethod> asyncMethodMap;
    final  RpcService proxyRpcService;

    public AsyncClient(RpcService proxyRpcService) {
        this.proxyRpcService = proxyRpcService;
        asyncMethodMap = new HashMap<>();
    }

    public void call(String method, Object param) {
        call(method, param, null);
    }

    public <DTO> void call(String method, Object param, ResultObserver<DTO> resultObserver) {
        var asyncMethod = asyncMethodMap.computeIfAbsent(method,k->AsyncMethod.from(proxyRpcService,k));
        asyncMethod.call(param,resultObserver);
    }
}