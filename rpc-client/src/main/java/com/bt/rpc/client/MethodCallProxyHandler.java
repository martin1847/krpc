package com.bt.rpc.client;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bt.rpc.annotation.Cached;
import com.bt.rpc.common.FilterChain;
import com.bt.rpc.common.MethodStub;
import com.bt.rpc.context.TraceMeta;
import com.bt.rpc.filter.FilterInvokeHelper;
import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.internal.SerialEnum;
import com.bt.rpc.model.RpcResult;
import com.bt.rpc.serial.ClientReader;
import com.bt.rpc.serial.ClientReader.Generic;
import com.bt.rpc.serial.ClientReader.Normal;
import com.bt.rpc.serial.ClientWriter;
import com.bt.rpc.serial.Serial;
import com.bt.rpc.util.RefUtils;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCalls;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

//import  sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl;

@Slf4j
public class MethodCallProxyHandler<T> implements InvocationHandler {

    private final ManagedChannel channel;
    final Class<T>       clz;

    final Map<Method, ChannelMethodInvoker> stubMap = new HashMap<>();

    //    private ClientFilter[] filters;

    FilterChain<ClientResult, ClientContext> filterChain;

    final CacheManager cacheManager;

    final SerialEnum serialEnum;
    final String     serverName;

    public MethodCallProxyHandler(String serverName, ManagedChannel channel, Class<T> clz,
                                  List<ClientFilter> filterList,
                                  CacheManager cacheManager,
                                  SerialEnum serialEnum) {
        this.serverName = serverName;
        this.channel = channel;
        this.clz = clz;

        this.filterChain = new FilterInvokeHelper<>
                (ClientContext.GLOBAL_FILTERS, filterList).buildFilterChain();

        this.cacheManager = cacheManager;
        this.serialEnum = serialEnum;

        initStub();
        log.info("[ RPC Client ] Proxy Init {}  methods for {}:{}", stubMap.size(), serverName,clz);
        log.info("GLOBAL_FILTERS  {} : {}",ClientContext.GLOBAL_FILTERS.size(),ClientContext.GLOBAL_FILTERS);
        log.info("Service_FILTERS {} : {}",filterList.size(),filterList);
    }

    class ChannelMethodInvoker implements FilterChain<ClientResult, ClientContext> {
        //        ClientCall<InputMessage, OutputMessage> clientCall;
        final MethodStub   stub;
        final ClientReader clientReader;
        final ClientWriter clientWriter;
        //        ManagedChannel channel;

        public ChannelMethodInvoker(MethodStub stub) {
            this.stub = stub;
            //hasInputs = stub.method.getParameterCount() > 0;
            if (0 == stub.method.getParameterCount()) {
                clientWriter = ClientWriter.ZERO_INPUT;
            } else if (byte[].class == stub.method.getParameterTypes()[0]) {
                clientWriter = ClientWriter.BYTES;
            } else {
                clientWriter = ClientWriter.BY_USER;
            }

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
            var input = buildInput(req.getSerial(),req.getArg());
            var response = rpc(req.getCallOptions(), input);
            return buildResult(req.getSerial(),response);
        }

        ClientResult buildResult(SerialEnum se,OutputProto response){
            return new ClientResult(response, clientReader, Serial.Instance.get(se.getNumber()));
        }

        InputProto buildInput(SerialEnum se,Object[] args){
            var input = InputProto.newBuilder();
            input.setE(se);
            clientWriter.writeParameters(args, input);
            return input.build();
        }


        protected OutputProto rpc(CallOptions options, InputProto input) {
            var call = makeCall (options);
            return ClientCalls.blockingUnaryCall(call, input);
        }

        public ClientCall<InputProto,OutputProto> makeCall(CallOptions options){
            var call = channel.newCall(stub.methodDescriptor, options);
            var traceId = MDC.get(TraceMeta.X_B3_TRACE_ID);
            if (null != traceId) {
                //log.debug("Client Propagate Trace : {}",traceId);
                return new PropagateTraceCall(call, traceId);
            }
            return call;
        }

    }

    private class CachedChannelMethodInvoker extends ChannelMethodInvoker {

        public CachedChannelMethodInvoker(MethodStub stub) {
            super(stub);
        }

        @Override
        protected OutputProto rpc(CallOptions options, InputProto input) {
            var cacheKey = cacheManager.cacheKey(stub, input);
            var out = cacheManager.get(stub, cacheKey);
            if (null != out) {
                return out;
            }
            out = super.rpc(options, input);
            if (out.getC() == RpcResult.OK) {
                cacheManager.set(stub, cacheKey, out);
            }
            return out;
        }
    }

    //(Lcom/bt/rpc/demo/service/HelloBean;Lcom/bt/rpc/demo/service/HelloBean;)
    // Lcom/bt/rpc/model/RpcResult<Lcom/bt/rpc/demo/service/HelloRes;>;
    private void initStub() {
        for (MethodStub stub : RefUtils.toRpcMethods(serverName, clz)) {

            if (cacheManager != null && stub.method.isAnnotationPresent(Cached.class)) {

                var expireSeconds = stub.method.getAnnotation(Cached.class).value();

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

        //maybe call toString
        if (null == cm) {
            return "UnSupported Method : " + clz + "." + method;
        }

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
