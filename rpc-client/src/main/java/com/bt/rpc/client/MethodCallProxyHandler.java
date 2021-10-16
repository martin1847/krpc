package com.bt.rpc.client;

import com.bt.rpc.common.FilterChain;
import com.bt.rpc.common.FilterInvokeHelper;
import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.model.Code;
import com.bt.rpc.util.MethodStub;
import com.bt.rpc.util.RefUtils;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCalls;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//import  sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl;

@Slf4j
public class MethodCallProxyHandler<T> implements InvocationHandler {

    private final ManagedChannel channel;
    private final Class<T> clz;

    private final Map<Method, ChannelMethodInvoker> stubMap = new HashMap<>();

//    private ClientFilter[] filters;

    FilterChain<ClientResult, ClientContext> filterChain;

    private final CacheManager cacheManager;

    public MethodCallProxyHandler(ManagedChannel channel, Class<T> clz, List<ClientFilter> filterList, CacheManager cacheManager) {
        this.channel = channel;
        this.clz = clz;

//        var flist  =  ClientContext.GLOBAL_FILTERS;
//
//        if(null != filterList && filterList.size() > 0){
//            var list = new ArrayList<ClientFilter>(flist.size() + filterList.size());
//            list.addAll(flist);
//            list.addAll(filterList);
//            flist = list;
//        }
//        var filters = flist.toArray(new ClientFilter[0]);
        this.filterChain = new FilterInvokeHelper<>
                (ClientContext.GLOBAL_FILTERS,filterList).buildFilterChain();

        this.cacheManager = cacheManager;


        initStub();
        log.info("[ RPC Client ] Proxy Init {}  methods for {}", stubMap.size(),clz);
    }




    @AllArgsConstructor
    private class ChannelMethodInvoker implements  FilterChain<ClientResult,ClientContext>{
//        ClientCall<InputMessage, OutputMessage> clientCall;
        MethodStub stub;
//        ManagedChannel channel;

        @Override
        public ClientResult invoke(ClientContext req) {
            var input = InputProto.newBuilder();
            stub.writeInput.accept(req.getArg(),input);

            OutputProto response = rpc(req, input.build());

//            var asyncRes =  remoteInvoker.AsyncUnaryCall(stub.GrpcMethod, null, option, input);
            // Console.WriteLine("rpcContext output {0}",output);
            //var output = asyncRes.GetAwaiter().GetResult();
            //var header =  asyncRes.ResponseHeadersAsync.GetAwaiter().GetResult();
            return new ClientResult(response,stub.readOutput);
        }

        protected OutputProto rpc(ClientContext req, InputProto input){
            var option = req.getCallOptions();
            var call = channel.newCall(stub.methodDescriptor, option);
            return ClientCalls.blockingUnaryCall(call, input);
        }
    }


    private class CachedChannelMethodInvoker extends ChannelMethodInvoker{

        public CachedChannelMethodInvoker(MethodStub stub) {
            super(stub);
        }

        @Override
        protected OutputProto rpc(ClientContext req, InputProto input){
            var cacheKey = cacheManager.cacheKey(stub,input);
            var out = cacheManager.get(stub,cacheKey);
            if(null != out){
                return out;
            }
            out = super.rpc(req,input);
            if(out.getC() == Code.OK.value) {
                cacheManager.set(stub,cacheKey, out);
            }
            return out;
        }
    }




    //(Lcom/bt/rpc/demo/service/HelloBean;Lcom/bt/rpc/demo/service/HelloBean;)Lcom/bt/rpc/model/RpcResult<Lcom/bt/rpc/demo/service/HelloRes;>;
    private void initStub() {
        for (MethodStub stub : RefUtils.toRpcMethods(clz)) {

            if(cacheManager!=null && stub.cached != null){

                var expireSeconds = stub.cached.value();
                if(expireSeconds<=0){
                    expireSeconds = stub.rpcService.expireSeconds();
                }
                if(expireSeconds<=0){
                    expireSeconds = cacheManager.expireSeconds();
                }
                if(expireSeconds<=0){
                    expireSeconds = CacheManager.DEFAULT_EXPIRE_SECONDS;
                }
                stub.setExpireSeconds(expireSeconds);

                stubMap.put(stub.method, new CachedChannelMethodInvoker(stub));
            }else {
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

        var reqContext = new ClientContext(clz,method.getName(),cm.stub.returnType,args, cm);

        ClientContext.LOCAL.set(reqContext);

        try {
            var res = filterChain.invoke(reqContext);
            return  res.toReturn();

        }finally {
            ClientContext.LOCAL.remove();
        }

    }

}
