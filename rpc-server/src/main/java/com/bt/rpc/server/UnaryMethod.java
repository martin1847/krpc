package com.bt.rpc.server;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import com.bt.rpc.annotation.UnsafeWeb;
import com.bt.rpc.annotation.UnsafeWeb.RequireCredential;
import com.bt.rpc.common.FilterChain;
import com.bt.rpc.common.MethodStub;
import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.model.RpcResult;
import com.bt.rpc.serial.Serial;
import com.bt.rpc.serial.ServerWriter;
import com.bt.rpc.server.invoke.DynamicInvoke;
import com.bt.rpc.server.invoke.GenericValidator;
import com.bt.rpc.server.invoke.NormalValidator;
import com.bt.rpc.util.EnvUtils;
import com.bt.rpc.util.RefUtils;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

@Slf4j
public class UnaryMethod implements io.grpc.stub.ServerCalls.UnaryMethod<InputProto, OutputProto> {
    private final Class                                    service;
    private final MethodStub                               stub;
    private final FilterChain<ServerResult, ServerContext> filterChain;

    private final String methodName;

    private DynamicInvoke invoke;


    //    static {
    //        // -J-Djava.util.logging.manager=org.jboss.logmanager.LogManager
    //        // java.vendor = GraalVM Community
    //        System.out.println("Java.vendor... :" + System.getProperty("java.vendor"));
    //        System.out.println("java.vm.name... :" + System.getProperty("java.vm.name"));
    //        System.out.println("Java.logging.manager :" + System.getProperty("java.util.logging.manager"));
    //    }

    private final boolean bytesWrite;

    private final boolean requireCredential;

    public UnaryMethod(Class service, Object serviceToInvoke, MethodStub stub, FilterChain<ServerResult, ServerContext> filterChain) {
        //this.serviceToInvoke = serviceToInvoke;
        this.stub = stub;
        this.filterChain = filterChain;
        this.service = service;
        methodName = stub.method.getName();

        if(stub.method.isAnnotationPresent(RequireCredential.class)){
            requireCredential = true;
        }else  if(service.isAnnotationPresent(UnsafeWeb.class) ){
            requireCredential = ((UnsafeWeb)service.getAnnotation(UnsafeWeb.class)).requireCredential();
        }else {
            requireCredential = false;
        }


        bytesWrite = stub.returnType == byte[].class;

        //stub.method.getGenericParameterTypes();
        var inputArgTypes = stub.method.getGenericParameterTypes();
        Type firstInputType = (inputArgTypes.length == 0) ? null : inputArgTypes[0];

        //"java.vm.name :Substrate VM" , graalVM just use Reflection
        /*
        [ Use Method Invoke ] with :Substrate VM
100 Exception , avgCost :1.78 ms
200 Exception , avgCost :1.31 ms
300 Exception , avgCost :1.17 ms
1000 Exception , avgCost :1.247 ms
2000 Exception , avgCost :1.003 ms
5000 Exception , avgCost :0.522 ms
10000 Exception , avgCost :0.3184 ms
20000 Exception , avgCost :0.3076 ms
         */

        var validator =  ServerContext.validator;
        if (System.getProperty("java.vm.name").contains("Substrate")) {
            if (firstInputType == null) {
                invoke = sc -> (RpcResult) stub.method.invoke(serviceToInvoke);
            } else if(byte[].class == firstInputType){
                log.debug("Found byte[] input  {} " ,stub.method.getName());

                invoke = sc -> (RpcResult) stub.method.invoke(serviceToInvoke, sc.getArg().getBs().toByteArray());
            }else if( firstInputType instanceof Class){

                var type = (Class)firstInputType;
                var needValidator =  validator !=null && RefUtils.needValidator(type);
                if(needValidator){
                    log.debug("set up validator for method {}({})",methodName,type);
                    invoke = new NormalValidator(validator, in -> (RpcResult) stub.method.invoke(serviceToInvoke,in),type );
                }else {
                    invoke = sc -> (RpcResult) stub.method.invoke(serviceToInvoke,DynamicInvoke.parseInput(sc,type));
                }

            }else{

                var pt = (ParameterizedType)firstInputType;
                log.debug("Found ParameterizedType {} " ,firstInputType);
                var raw = (Class)pt.getRawType();
                var needValidator =  validator !=null && RefUtils.needValidator(raw);
                if(needValidator){
                    log.debug("set up validator for method {}({})",methodName,pt);
                    invoke = new GenericValidator(validator, in -> (RpcResult) stub.method.invoke(serviceToInvoke,in),pt);
                }else {
                    invoke = sc -> (RpcResult) stub.method.invoke(serviceToInvoke, DynamicInvoke.parseInput(sc, pt));
                }
            }
            return;
        }

        var publicLookup = MethodHandles.publicLookup();
        //        var mt = MethodType.methodType(stub.method.getReturnType(),stub.method.getParameterTypes());

        /**
         * 100 Exception , avgCost :1.66 ms
         * 200 Exception , avgCost :1.345 ms
         * 300 Exception , avgCost :1.21 ms
         * 1000 Exception , avgCost :1.276 ms
         * 2000 Exception , avgCost :1.181 ms
         * 5000 Exception , avgCost :0.6622 ms
         * 10000 Exception , avgCost :0.3655 ms
         * 20000 Exception , avgCost :0.3569 ms
         */
        try {
            //var mh = publicLookup.findVirtual(service,stub.method.getName(),mt).bindTo(serviceToInvoke);

            // vs JavassistProxyFactory https://bytebuddy.net/#/
            var mh = publicLookup.unreflect(stub.method).bindTo(serviceToInvoke);

            if (firstInputType == null) {
                // compiler time
                invoke = sc -> (RpcResult) mh.invokeExact();
            } else if(byte[].class == firstInputType){

                log.debug("found byte[] input MH {} " ,stub.method.getName());
                invoke = sc -> (RpcResult) mh.invokeExact(sc.getArg().getBs().toByteArray());
            } else if( firstInputType instanceof Class){

                // may has method override issue , int / string  both change to Object Param,
                //                var objInputMh=mh.asType(mh.type().changeParameterType(0, Object.class));
                //                invoke = sc ->  (RpcResult) objInputMh.invokeExact(sc.readInput.apply(sc.getArg()));

                var type = (Class)firstInputType;
                var needValidator =  validator !=null && RefUtils.needValidator(type);
                if(needValidator){
                    log.debug("set up validator for method {}({})",methodName,type);
                    invoke = new NormalValidator(validator, in->(RpcResult) mh.invoke(in),type);
                }else {
                    invoke = sc -> (RpcResult) mh.invoke( DynamicInvoke.parseInput(sc,type) );
                }
            }else{
                var pt = (ParameterizedType)firstInputType;
                log.debug("Found ParameterizedType {} " ,firstInputType);
                if(validator !=null && RefUtils.needValidator((Class)pt.getRawType())){

                    log.debug("set up validator for method {}({})",methodName,pt);
                    invoke = new GenericValidator(validator, in->(RpcResult) mh.invoke(in),pt);
                }else {
                    invoke = sc -> (RpcResult) mh.invoke(DynamicInvoke.parseInput(sc, pt));
                }
            }

        } catch (Exception e) {
            log.error("Init server side MethodHandles error, change To RuntimeException : ", e);
            throw new RuntimeException(e);
        }

    }

    public ServerResult invoke(ServerContext req) throws Throwable {
        var res = invoke.invoke(req);
        return new ServerResult(res, bytesWrite ? ServerWriter.BYTES : Serial.Instance.get(req.getArg().getEValue()));
    }

    static final int MAX_ERROR_LENGTH = 100;
    // https://github.com/openzipkin/brave
    // https://quarkus.io/guides/logging
    // [%X{traceId}/%X{spanId}]
    @Override
    public void invoke(InputProto im, StreamObserver<OutputProto> responseObserver) {

        var ctx = new ServerContext(service, methodName, stub.returnType,
                im, this::invoke, ((UnaryCallObserver) responseObserver).getHeaders());


        //1.  https://github.com/perfmark/perfmark
        //try (TaskCloseable task = PerfMark.traceTask("Parse HTTP headers")) {

        //2. https://github.com/grpc/grpc-java/issues/7381
        // https://github.com/LesnyRumcajs/grpc_bench/wiki/2021-05-20-bench-results
        try {
            /// first thing set the Context so that all after method can use
            ServerContext.LOCAL.set(ctx);

            if(requireCredential){
                ctx.checkCredential();
            }

            var res = filterChain.invoke(ctx);
            responseObserver.onNext(res.output);
            responseObserver.onCompleted();
        } catch (Throwable ex) {
            var traceId = ctx.logTrace();
            log.error(traceId , ex);

            Throwable wrapToClient = ex;
            if (ex instanceof InvocationTargetException) {
                wrapToClient = ex.getCause();
            }
            if (!(wrapToClient instanceof StatusException) && !(wrapToClient instanceof StatusRuntimeException)) {
                //Server side application throws an exception (or does something other than returning a Status code to terminate an RPC)
                //https://grpc.github.io/grpc/core/md_doc_statuscodes.html
                var errMsg = wrapToClient.getMessage();
                if(null != errMsg && errMsg.length() > MAX_ERROR_LENGTH){
                    errMsg = errMsg.substring(0,MAX_ERROR_LENGTH)+"...";
                }
                wrapToClient = Status.UNKNOWN.withDescription(
                                traceId + "," + wrapToClient.getClass().getSimpleName() + "," + errMsg)
                        .withCause(wrapToClient).asRuntimeException();
            }
            responseObserver.onError(wrapToClient);
        } finally {
            ServerContext.LOCAL.remove();
            //MDC.clear();
        }
    }
}
