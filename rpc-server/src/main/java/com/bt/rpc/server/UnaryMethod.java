package com.bt.rpc.server;

import com.bt.rpc.common.FilterChain;
import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;
import com.bt.rpc.model.RpcResult;
import com.bt.rpc.serial.Serial;
import com.bt.rpc.serial.ServerSerial;
import com.bt.rpc.util.EnvUtils;
import com.bt.rpc.common.MethodStub;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;

@Slf4j
public class UnaryMethod implements io.grpc.stub.ServerCalls.UnaryMethod<InputProto, OutputProto> {
    private final Class                                    service;
    //private final Object serviceToInvoke;
    private final MethodStub                               stub;
    private final FilterChain<ServerResult, ServerContext> filterChain;

    private final String methodName;

    private DynamicInvoke invoke;

    private ServerSerial serverSerial;

    //    static {
    //        // -J-Djava.util.logging.manager=org.jboss.logmanager.LogManager
    //        // java.vendor = GraalVM Community
    //        System.out.println("Java.vendor... :" + System.getProperty("java.vendor"));
    //        System.out.println("java.vm.name... :" + System.getProperty("java.vm.name"));
    //        System.out.println("Java.logging.manager :" + System.getProperty("java.util.logging.manager"));
    //    }

    public UnaryMethod(Class service, Object serviceToInvoke, MethodStub stub, FilterChain<ServerResult, ServerContext> filterChain) {
        //this.serviceToInvoke = serviceToInvoke;
        this.stub = stub;
        this.filterChain = filterChain;
        this.service = service;
        methodName = stub.method.getName();

        serverSerial =  (byte[].class == stub.returnType)? ServerSerial.BARE : Serial.Instance.get();

        var inputArgTypes = stub.method.getParameterTypes();
        Class firstInputType = (inputArgTypes.length == 0) ? null : inputArgTypes[0];

        //"java.vm.name :Substrate VM" , graalVM just use Reflection
        if (System.getProperty("java.vm.name").contains("Substrate")) {
            if (firstInputType == null) {
                invoke = sc -> (RpcResult) stub.method.invoke(serviceToInvoke);
            } else {

                invoke = sc -> (RpcResult) stub.method.invoke(serviceToInvoke,
                        serverSerial.readInput(sc.getArg(),firstInputType));
            }
            return;
        }

        var publicLookup = MethodHandles.publicLookup();
        //        var mt = MethodType.methodType(stub.method.getReturnType(),stub.method.getParameterTypes());

        try {
            //var mh = publicLookup.findVirtual(service,stub.method.getName(),mt).bindTo(serviceToInvoke);

            // vs JavassistProxyFactory https://bytebuddy.net/#/
            var mh = publicLookup.unreflect(stub.method).bindTo(serviceToInvoke);

            if (firstInputType == null) {
                // compiler time
                invoke = sc -> (RpcResult) mh.invokeExact();
            } else {

                // may has method override issue , int / string  both change to Object Param,
                //                var objInputMh=mh.asType(mh.type().changeParameterType(0, Object.class));
                //                invoke = sc ->  (RpcResult) objInputMh.invokeExact(sc.readInput.apply(sc.getArg()));
                invoke = sc -> (RpcResult) mh.invoke(serverSerial.readInput(sc.getArg(),firstInputType));
            }

        } catch (Exception e) {
            log.error("Init server side MethodHandles error, change To RuntimeException : ", e);
            throw new RuntimeException(e);
        }

    }

    @FunctionalInterface
    interface DynamicInvoke<DTO> {

        RpcResult<DTO> invoke(ServerContext req) throws Throwable;

    }

    public ServerResult invoke(ServerContext req) throws Throwable {
        var res = invoke.invoke(req);
        return new ServerResult(res, serverSerial);
    }

    // https://github.com/openzipkin/brave

    static final Key<String> TRACE_ID = Metadata.Key.of(EnvUtils.env("TRACE_ID", "x-b3-traceid")
            , Metadata.ASCII_STRING_MARSHALLER);
    static final Key<String> SPAN_ID  = Metadata.Key.of(EnvUtils.env("SPAN_ID", "x-b3-spanid")
            , Metadata.ASCII_STRING_MARSHALLER);

    //static final String      HOST_NAME = ;
    // https://quarkus.io/guides/logging
    // TODO json MDC
    // [%X{traceId}/%X{spanId}]
    @Override
    public void invoke(InputProto im, StreamObserver<OutputProto> responseObserver) {

        var ctx = new ServerContext(service, methodName, stub.returnType,
                im, this::invoke, serverSerial, ((UnaryCallObserver) responseObserver).getHeaders());
        ServerContext.LOCAL.set(ctx);

        //1.  https://github.com/perfmark/perfmark
        //try (TaskCloseable task = PerfMark.traceTask("Parse HTTP headers")) {

        //2. https://github.com/grpc/grpc-java/issues/7381
        // https://github.com/LesnyRumcajs/grpc_bench/wiki/2021-05-20-bench-results
        try {
            var res = filterChain.invoke(ctx);
            responseObserver.onNext(res.output);
            responseObserver.onCompleted();
        } catch (Throwable ex) {
            // will cached by GraalVM
            var headers = ctx.getHeaders();
            var msg = EnvUtils.hostName() + ":" + headers.get(TRACE_ID);
            log.error(msg + ":" + headers.get(SPAN_ID), ex);

            Throwable wrapToClient = ex;
            if (ex instanceof InvocationTargetException) {
                wrapToClient = ex.getCause();
            }
            if (!(wrapToClient instanceof StatusException) && !(wrapToClient instanceof StatusRuntimeException)) {
                wrapToClient = Status.INTERNAL.withDescription(
                                msg + " - " + wrapToClient.getClass().getName() + ": " + wrapToClient.getMessage())
                        .withCause(wrapToClient).asRuntimeException();
            }
            responseObserver.onError(wrapToClient);
        } finally {
            ServerContext.LOCAL.remove();

        }
    }
}
