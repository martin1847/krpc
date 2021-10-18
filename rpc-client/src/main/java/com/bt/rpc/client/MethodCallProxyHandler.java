package com.bt.rpc.client;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bt.rpc.common.FilterChain;
import com.bt.rpc.common.FilterInvokeHelper;
import com.bt.rpc.common.MethodStub;
import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.internal.SerialEnum;
import com.bt.rpc.model.RpcResult;
import com.bt.rpc.serial.ClientReader;
import com.bt.rpc.serial.ClientReader.Generic;
import com.bt.rpc.serial.ClientReader.Normal;
import com.bt.rpc.serial.Serial;
import com.bt.rpc.util.RefUtils;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCalls;
import lombok.extern.slf4j.Slf4j;

//import  sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl;

@Slf4j
public class MethodCallProxyHandler<T> implements InvocationHandler {

    private final ManagedChannel channel;
    private final Class<T>       clz;

    private final Map<Method, ChannelMethodInvoker> stubMap = new HashMap<>();

    //    private ClientFilter[] filters;

    FilterChain<ClientResult, ClientContext> filterChain;

    private final CacheManager cacheManager;

    private final SerialEnum serialEnum;

    public MethodCallProxyHandler(ManagedChannel channel, Class<T> clz,
                                  List<ClientFilter> filterList,
                                  CacheManager cacheManager,
                                  SerialEnum serialEnum) {
        this.channel = channel;
        this.clz = clz;

        this.filterChain = new FilterInvokeHelper<>
                (ClientContext.GLOBAL_FILTERS, filterList).buildFilterChain();

        this.cacheManager = cacheManager;
        this.serialEnum = serialEnum;

        initStub();
        log.info("[ RPC Client ] Proxy Init {}  methods for {}", stubMap.size(), clz);
    }

    private class ChannelMethodInvoker implements FilterChain<ClientResult, ClientContext> {
        //        ClientCall<InputMessage, OutputMessage> clientCall;
        final MethodStub   stub;
        final boolean      hasInputs;
        final ClientReader clientReader;
        //        ManagedChannel channel;

        public ChannelMethodInvoker(MethodStub stub) {
            this.stub = stub;
            hasInputs = stub.method.getParameterCount() > 0;
            var returnType = stub.returnType;
            if (byte[].class == returnType) {
                clientReader = ClientReader.BARE;
            } else if (returnType instanceof Class) {
                clientReader = new Normal((Class) returnType);
            } else {
                clientReader = new Generic((ParameterizedType) returnType);
            }
        }

        @Override
        public ClientResult invoke(ClientContext req) {
            var serial = Serial.Instance.get(req.getSerial().getNumber());

            var input = InputProto.newBuilder();
            input.setE(req.getSerial());
            if (hasInputs) {
                serial.writeInput(req.getArg()[0], input);
            }
            OutputProto response = rpc(req, input.build());
            return new ClientResult(response, clientReader, serial);
        }

        protected OutputProto rpc(ClientContext req, InputProto input) {
            var option = req.getCallOptions();
            var call = channel.newCall(stub.methodDescriptor, option);
            return ClientCalls.blockingUnaryCall(call, input);
        }
    }

    private class CachedChannelMethodInvoker extends ChannelMethodInvoker {

        public CachedChannelMethodInvoker(MethodStub stub) {
            super(stub);
        }

        @Override
        protected OutputProto rpc(ClientContext req, InputProto input) {
            var cacheKey = cacheManager.cacheKey(stub, input);
            var out = cacheManager.get(stub, cacheKey);
            if (null != out) {
                return out;
            }
            out = super.rpc(req, input);
            if (out.getC() == RpcResult.OK) {
                cacheManager.set(stub, cacheKey, out);
            }
            return out;
        }
    }

    //(Lcom/bt/rpc/demo/service/HelloBean;Lcom/bt/rpc/demo/service/HelloBean;)
    // Lcom/bt/rpc/model/RpcResult<Lcom/bt/rpc/demo/service/HelloRes;>;
    private void initStub() {
        for (MethodStub stub : RefUtils.toRpcMethods(clz)) {

            if (cacheManager != null && stub.cached != null) {

                var expireSeconds = stub.cached.value();
                if (expireSeconds <= 0) {
                    expireSeconds = stub.rpcService.expireSeconds();
                }
                if (expireSeconds <= 0) {
                    expireSeconds = cacheManager.expireSeconds();
                }
                if (expireSeconds <= 0) {
                    expireSeconds = CacheManager.DEFAULT_EXPIRE_SECONDS;
                }
                stub.setExpireSeconds(expireSeconds);

                stubMap.put(stub.method, new CachedChannelMethodInvoker(stub));
            } else {
                stubMap.put(stub.method, new ChannelMethodInvoker(stub));
            }

            //            ClientCall<InputMessage, OutputMessage> newCall = channel.newCall(stub.methodDescriptor, CallOptions.DEFAULT);

        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
            throws Throwable {

        var cm = stubMap.get(method);

        //        if(null == cm){
        //            return  "UnSupported Method : " + method;
        //        }

        var reqContext = new ClientContext(clz, method.getName(), cm.stub.returnType
                , args, cm, serialEnum);

        ClientContext.LOCAL.set(reqContext);

        try {
            var res = filterChain.invoke(reqContext);
            return res.toReturn();

        } finally {
            ClientContext.LOCAL.remove();
        }

    }

}
