/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package tech.krpc.client;

import java.lang.reflect.Proxy;
import java.util.Map.Entry;

import tech.krpc.internal.OutputProto;
import tech.krpc.internal.SerialEnum;
import tech.krpc.model.RpcResult;
import io.grpc.CallOptions;
import io.grpc.Context;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Martin.C
 * @version 2022/01/07 4:03 PM
 */
@Slf4j
@Deprecated(since = "跟随父生命周期，父亲结束之后异步接口会被取消")
public class AsyncMethod<RpcService, Input, DTO> {

    final MethodCallProxyHandler<RpcService>.ChannelMethodInvoker invoker;
    final SerialEnum                                              serialEnum;
    final MethodStreamObserver                                    defaultObserver;

    AsyncMethod(MethodCallProxyHandler<RpcService>.ChannelMethodInvoker invoker,
                SerialEnum serialEnum, Class<RpcService> rpcService, String method) {
        this.invoker = invoker;
        this.serialEnum = serialEnum;
        defaultObserver = new MethodStreamObserver(rpcService.getSimpleName() + "." + method);

        //This can be done using StackWalker since Java 9.
        //public static String getCurrentMethodName() {
        //    return StackWalker.getInstance()
        //            .walk(s -> s.skip(1).findFirst())
        //            .get()
        //            .getMethodName();
        //}

    }

    public static <RpcService, Input, DTO> AsyncMethod<RpcService, Input, DTO> from(RpcService service, String method) {
        var handler = (MethodCallProxyHandler<RpcService>) Proxy.getInvocationHandler(service);
        var invoker = handler.stubMap.entrySet().stream()
                .filter(kv -> kv.getKey().getName().equals(method))
                .findFirst().map(Entry::getValue).orElseThrow();
        SerialEnum serialEnum = handler.serialEnum;
        return new AsyncMethod<>(invoker, serialEnum, handler.clz, method);
    }

    public void call(Input param) {
        call(param, null);
    }

    public void call(Input param, ResultObserver<DTO> resultObserver) {
        var input = invoker.buildInput(serialEnum, param == null ? null : new Object[] {param});

        var observer = resultObserver == null ? defaultObserver : new StreamObserver<OutputProto>() {

            @Override
            public void onNext(OutputProto value) {
                var res = invoker.buildResult(serialEnum, value);
                resultObserver.onSuccess(res.toReturn());
            }

            @Override
            public void onError(Throwable t) {
                resultObserver.onError(t);
            }

            @Override
            public void onCompleted() {
                resultObserver.onCompleted();
            }
        };
        //https://stackoverflow.com/questions/57110811/grpc-random-cancelled-exception-on-rpc-calls

        // Set ctx as the current context within the Runnable
        ////生命周期仍然跟随父Context
        var call = invoker.makeCall(CallOptions.DEFAULT);
        Context ctx = Context.current().fork();
        ctx.run(() -> {
            // Can start asynchronous work here that will not
            // be cancelled when myRpcMethod returns
            ClientCalls.asyncUnaryCall(call, input, observer);
        });

    }

    static class MethodStreamObserver implements StreamObserver<OutputProto> {

        final String methodId;

        public MethodStreamObserver(String methodId) {
            this.methodId = methodId;
        }

        @Override
        public void onNext(OutputProto value) {
            log.debug("Async call {} , code: {}, message: {}", methodId, value.getC(), value.getM());
        }

        @Override
        public void onError(Throwable t) {
            log.warn("Async call " + methodId + " error", t);
        }

        @Override
        public void onCompleted() {

        }
    }

    public interface ResultObserver<DTO> {
        void onError(Throwable t);

        void onSuccess(RpcResult<DTO> res);

        default void onCompleted() {
        }
    }
}