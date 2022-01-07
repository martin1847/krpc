/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.bt.rpc.client;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.internal.SerialEnum;
import com.bt.rpc.model.RpcResult;
import io.grpc.CallOptions;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Martin.C
 * @version 2022/01/07 2:06 PM
 */
@Slf4j
public class AsyncClient<T> {

    final Map<String, MethodCallProxyHandler<T>.ChannelMethodInvoker> stubMap;
    final SerialEnum                                                  serialEnum;
    final Class<T> rpcService;

    public AsyncClient(T proxyRpcService) {
        var handler = (MethodCallProxyHandler<T>) Proxy.getInvocationHandler(proxyRpcService);
        stubMap = new HashMap<>(handler.stubMap.size());
        serialEnum = handler.serialEnum;
        rpcService = handler.clz;
        for (Entry<Method, MethodCallProxyHandler<T>.ChannelMethodInvoker> kv : handler.stubMap.entrySet()) {
            stubMap.put(kv.getKey().getName(), kv.getValue());
        }
    }

    public void call(String method, Object param) {
        call(method, param, null);
    }

    public <DTO> void call(String method, Object param, ResultObserver<DTO> resultObserver) {
        var invoker = stubMap.get(method);
        var input = invoker.buildInput(serialEnum, param == null ? null : new Object[] {param});
        var call = invoker.makeCall(CallOptions.DEFAULT);
        ClientCalls.asyncUnaryCall(call, input, new StreamObserver<>() {

            @Override
            public void onNext(OutputProto value) {
                if (null != resultObserver) {
                    var res = invoker.buildResult(serialEnum, value);
                    resultObserver.onSuccess(res.toReturn());
                }
            }

            @Override
            public void onError(Throwable t) {
                if (null != resultObserver) {
                    resultObserver.onError(t);
                } else {
                    log.error("async call " +rpcService.getSimpleName() +"."+ method + " error", t);
                }
            }

            @Override
            public void onCompleted() {
                if (null != resultObserver) {resultObserver.onCompleted();}
            }
        });
    }

    public interface ResultObserver<DTO> {
        void onError(Throwable t);

        void onSuccess(RpcResult<DTO> res);

        default void onCompleted() {
        }
    }
}