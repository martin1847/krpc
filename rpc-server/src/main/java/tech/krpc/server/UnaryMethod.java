package tech.krpc.server;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.annotation.UnsafeWeb.RequireCredential;
import tech.krpc.common.FilterChain;
import tech.krpc.common.MethodStub;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;
import tech.krpc.model.RpcResult;
import tech.krpc.serial.Serial;
import tech.krpc.serial.ServerWriter;
import tech.krpc.server.invoke.DynamicInvoke;
import tech.krpc.server.invoke.GenericValidator;
import tech.krpc.server.invoke.NormalValidator;
import tech.krpc.util.RefUtils;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

@Slf4j
public class UnaryMethod implements io.grpc.stub.ServerCalls.UnaryMethod<InputProto, OutputProto>, WebInvoker {
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

                invoke = sc -> (RpcResult) stub.method.invoke(serviceToInvoke, (Object) sc.getArg().getBs());
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
                invoke = sc -> (RpcResult) mh.invokeExact(sc.getArg().getBs());
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

    /**
     * ADR-0004 (AGENT-001 P0): synchronous web/agent entry that reuses the exact gRPC
     * credential + filter-chain dispatch of {@link #invoke(InputProto, StreamObserver)},
     * minus the gRPC StreamObserver wiring. Single-sources the security path so the HTTP
     * invoke endpoint can never bypass {@code requireCredential} or the filter chain.
     */
    @Override
    public ServerResult invokeWeb(InputProto im, Metadata headers) throws Throwable {
        var ctx = new ServerContext(service, methodName, stub.returnType, im, this::invoke, headers);
        io.grpc.Context gctx = io.grpc.Context.current().withValue(ServerContext.SC_KEY, ctx);
        io.grpc.Context prev = gctx.attach();
        try {
            if (requireCredential) {
                ctx.checkCredential();
            }
            return filterChain.invoke(ctx);
        } catch (Throwable ex) {
            // AGENT-ERRCODE: the HTTP faces get the SAME exception -> Status mapping as gRPC.
            // Before this, invokeWeb let the raw exception escape, so /agent/invoke reported a
            // hardcoded INTERNAL(13) and MCP got Status.fromThrowable's UNKNOWN(2) default -- both
            // labelling a client input error as a server fault. One mapping, one answer per face.
            var traceId = ctx.logTrace();
            var mapped = toClientError(ex, traceId);
            logDispatchFailure(traceId, mapped, ex);
            throw mapped;
        } finally {
            gctx.detach(prev);
            MDC.clear();
        }
    }

    /**
     * AGENT-ERRCODE: the SINGLE exception -> gRPC {@link Status} mapping, shared by the gRPC
     * {@link #invoke} path and the HTTP {@link #invokeWeb} path (which both agent faces dispatch
     * through). Keeping one table is the point: three call sites each inventing their own code was
     * how {@code /agent/invoke} ended up answering INTERNAL(13) for a malformed request body.
     *
     * <table>
     *   <tr><th>input</th><th>result</th></tr>
     *   <tr><td>{@code InvocationTargetException}</td><td>unwrapped, then classified as below</td></tr>
     *   <tr><td>{@link tech.krpc.util.JsonDecodeException}</td><td>{@code INVALID_ARGUMENT}(3), sanitized description</td></tr>
     *   <tr><td>{@code StatusException} / {@code StatusRuntimeException}</td><td>passed through unchanged
     *       — this is the branch {@code ValidationException} (an {@code INVALID_ARGUMENT}
     *       {@code StatusRuntimeException}) and every auth failure travel on</td></tr>
     *   <tr><td>anything else</td><td>{@code UNKNOWN}(2), {@code traceId,Class,message} truncated</td></tr>
     * </table>
     *
     * <p>Note there is no {@code INTERNAL}(13) row: an unexpected server-side exception has always
     * been {@code UNKNOWN} on the gRPC face, and the HTTP faces now agree with it rather than
     * inventing a different code.
     */
    static Throwable toClientError(Throwable ex, String traceId) {
        Throwable wrapToClient = ex;
        if (ex instanceof InvocationTargetException) {
            wrapToClient = ex.getCause();
        }
        if (wrapToClient instanceof tech.krpc.util.JsonDecodeException) {
            // AUD-omp-31: a malformed request body is the CLIENT's fault -> INVALID_ARGUMENT, not
            // UNKNOWN/500. Description is sanitized (no Jackson field/class names); the full cause
            // is already in the caller's log.error for server-side diagnosis.
            return Status.INVALID_ARGUMENT
                    .withDescription(describe(traceId, "malformed JSON request body"))
                    .withCause(wrapToClient).asRuntimeException();
        }
        if (wrapToClient instanceof StatusException || wrapToClient instanceof StatusRuntimeException) {
            return wrapToClient;
        }
        //Server side application throws an exception (or does something other than returning a Status code to terminate an RPC)
        //https://grpc.github.io/grpc/core/md_doc_statuscodes.html
        var errMsg = wrapToClient.getMessage();
        if (null != errMsg && errMsg.length() > MAX_ERROR_LENGTH) {
            errMsg = errMsg.substring(0, MAX_ERROR_LENGTH) + "...";
        }
        return Status.UNKNOWN.withDescription(
                        describe(traceId, wrapToClient.getClass().getSimpleName() + "," + errMsg))
                .withCause(wrapToClient).asRuntimeException();
    }

    /**
     * Join the correlation prefix onto a client-visible description, omitting it entirely when
     * there is no trace to correlate with. {@code ServerContext.logTrace()} returns "" in that
     * case, so a naive concatenation would emit a bare leading comma -- and before that returned
     * "", a literal {@code ":null,"}.
     */
    private static String describe(String traceId, String detail) {
        return traceId.isEmpty() ? detail : traceId + "," + detail;
    }

    /**
     * Log a dispatch failure at the level its CLASS warrants, not at one level for everything.
     *
     * <p>An expected client error — a malformed body, a failed validation, a rejected credential —
     * gets one bounded line and no stack. Three reasons, all of which bit us:
     * <ul>
     *   <li>a stack trace of a {@code JsonDecodeException} contains the Jackson cause, which quotes
     *       the rejected scalar and surrounding input;</li>
     *   <li>auth failures are attacker-triggerable, so a full stack per rejected request is a log
     *       amplification lever;</li>
     *   <li>they are not faults. ERROR on a caller's typo trains operators to ignore ERROR.</li>
     * </ul>
     *
     * <p>Only a genuinely unknown failure keeps the full throwable — that one IS a fault, nobody
     * else records it, and its cause chain is the whole point.
     */
    private static void logDispatchFailure(String traceId, Throwable mapped, Throwable original) {
        var code = Status.fromThrowable(mapped).getCode();
        if (Status.Code.UNKNOWN == code) {
            log.error(traceId, original);
            return;
        }
        log.warn("{} dispatch rejected: {} {}", traceId, code, original.getClass().getSimpleName());
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
        /// first thing set the Context so that all after method can use
        io.grpc.Context gctx = io.grpc.Context.current().withValue(ServerContext.SC_KEY, ctx);
        io.grpc.Context prev = gctx.attach();
        try {
            if(requireCredential){
                ctx.checkCredential();
            }

            var res = filterChain.invoke(ctx);
            responseObserver.onNext(res.output);
            responseObserver.onCompleted();
        } catch (Throwable ex) {
            var traceId = ctx.logTrace();
            var mapped = toClientError(ex, traceId);
            logDispatchFailure(traceId, mapped, ex);
            responseObserver.onError(mapped);
        } finally {
            gctx.detach(prev);
            MDC.clear();
        }
    }
}
