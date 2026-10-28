package tech.krpc.client;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tech.krpc.annotation.Cached;
import tech.krpc.common.FilterChain;
import tech.krpc.common.MethodStub;
import tech.krpc.context.KrpcOtel;
import tech.krpc.context.TraceMeta;
import tech.krpc.filter.FilterInvokeHelper;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;
import tech.krpc.internal.SerialEnum;
import tech.krpc.model.RpcResult;
import tech.krpc.serial.ClientReader;
import tech.krpc.serial.ClientReader.Generic;
import tech.krpc.serial.ClientReader.Normal;
import tech.krpc.serial.ClientWriter;
import tech.krpc.serial.Serial;
import tech.krpc.util.RefUtils;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCalls;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

//import  sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl;

@Slf4j
public class MethodCallProxyHandler<T> implements InvocationHandler {

    // OTEL-001 (ADR-0006): the RPC channel, wrapped with the OTel CLIENT interceptor when enabled.
    // Widened to io.grpc.Channel (ClientInterceptors.intercept returns a Channel, not a
    // ManagedChannel); only newCall() is used here, and channel lifecycle stays with the caller's
    // ManagedChannel (RpcClientFactory.close()).
    private final io.grpc.Channel channel;
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
        // OTEL-001 (ADR-0006): install the CLIENT interceptor (CLIENT span + W3C traceparent
        // injection). Default ON; no-op without an OTel SDK. When off, the raw channel is used —
        // ADR-0003 MDC forwarding is untouched.
        this.channel = KrpcOtel.enabled()
                ? io.grpc.ClientInterceptors.intercept(channel, new OtelClientInterceptor())
                : channel;
        this.clz = clz;

        this.filterChain = new FilterInvokeHelper<>
                (ClientContext.GLOBAL_FILTERS, filterList).buildFilterChain();

        this.cacheManager = cacheManager;
        this.serialEnum = serialEnum;

        initStub();
        log.info("[ RPC Client ] Proxy Init {}  methods for {}:{}."
                , stubMap.size(), serverName,clz);
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
            // O2 (HARDEN-B2): apply the configurable default deadline as the last step before the
            // call is created, so an explicit OPTION_LOCAL / withCallOptions / filter-set deadline
            // (already on `options`) wins and only a truly deadline-less call gets the default.
            var call = channel.newCall(stub.methodDescriptor, ClientDeadline.apply(options));
            // ADR-0003: propagate the inbound W3C traceparent to the outbound call.
            var traceparent = MDC.get(TraceMeta.MDC_TRACEPARENT);
            if (null != traceparent) {
                //log.debug("Client Propagate Trace : {}",traceparent);
                return new PropagateTraceCall(call, traceparent);
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
