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
     * Statuses that do NOT get a stack trace: the request was refused, not the server broken.
     *
     * <p>Two independent reasons, and a code qualifies on either:
     * <ul>
     *   <li><b>It is the caller's fault.</b> A malformed body, a failed validation, a rejected
     *       credential, a request for something that does not exist. These are not faults, and
     *       ERROR-with-stack on a caller's typo trains operators to ignore ERROR. Worse, a
     *       {@code JsonDecodeException} stack contains the Jackson cause, which quotes the rejected
     *       scalar and its surrounding input straight into the log.</li>
     *   <li><b>An attacker can trigger it at will.</b> Rejected credentials, rate limiting, probing
     *       for unimplemented methods, and client disconnects are all reachable by anyone who can
     *       reach the port. A stack trace each is a log-amplification lever.</li>
     * </ul>
     *
     * <p>{@code RESOURCE_EXHAUSTED}, {@code UNIMPLEMENTED} and {@code CANCELLED} are here on the
     * SECOND reason even though their message is withheld from the client as if they were ours:
     * disclosure and logging are independent axes, and the answer differs. Quota rejection,
     * endpoint scanning and client hang-ups are routine, unbounded in volume, and their stack says
     * nothing a bounded line does not.
     */
    private static final java.util.Set<Status.Code> LOG_WITHOUT_STACK = java.util.EnumSet.of(
            Status.Code.INVALID_ARGUMENT,
            Status.Code.NOT_FOUND,
            Status.Code.ALREADY_EXISTS,
            Status.Code.FAILED_PRECONDITION,
            Status.Code.OUT_OF_RANGE,
            Status.Code.UNAUTHENTICATED,
            Status.Code.PERMISSION_DENIED,
            Status.Code.UNAVAILABLE,
            Status.Code.RESOURCE_EXHAUSTED,
            Status.Code.UNIMPLEMENTED,
            Status.Code.CANCELLED);

    /**
     * Log a dispatch failure at the level its CLASS warrants, not one level for everything.
     *
     * <p>The status code alone is too coarse to decide this, because several codes are genuinely
     * ambiguous: {@code RESOURCE_EXHAUSTED} is a quota refusal or a full disk;
     * {@code UNAVAILABLE} is a not-yet-loaded JWKS or a dependency that fell over. So the code
     * picks the floor and <b>the presence of a cause raises it</b>:
     *
     * <ul>
     *   <li><b>A cause means something actually threw.</b> Whoever built that status was reporting
     *       a failure, not making a decision, so it gets the full stack whatever the code says.</li>
     *   <li><b>No cause means the status was constructed deliberately</b> —
     *       {@code Status.RESOURCE_EXHAUSTED.withDescription("daily quota")} is a refusal someone
     *       decided to return. One bounded line.</li>
     * </ul>
     *
     * <p>This keeps both properties that pull against each other. A flood against a rate limiter
     * trips the deliberate, cause-less path, so it cannot be used as a log-amplification lever; an
     * {@code IOException} surfaced as {@code UNAVAILABLE} keeps the cause chain that is the only
     * way to diagnose it. And unlike a code-only rule it reads a signal that is really in the
     * data, rather than guessing intent from an enum.
     *
     * <p><b>{@code INVALID_ARGUMENT} never upgrades</b>, cause or not. Its cause is not a fault
     * report: {@code toClientError} attaches the decode failure itself, whose Jackson chain quotes
     * the rejected scalar and the input around it. The exception IS the refusal there, and its
     * payload is caller data — the one code where a cause is expected and means the opposite of a
     * server fault.
     */
    static void logDispatchFailure(String traceId, Throwable mapped, Throwable original) {
        var status = Status.fromThrowable(mapped);
        var code = status.getCode();
        if (LOG_WITHOUT_STACK.contains(code) && !isFaultReport(status, code)) {
            log.warn("{} dispatch rejected: {} {}{}", traceId, code,
                    original.getClass().getSimpleName(), loggableDescription(status, code));
            return;
        }
        log.error("{} dispatch failed: {}", traceId, code, original);
    }

    /** A cause on anything but {@code INVALID_ARGUMENT} marks this as a real failure. */
    private static boolean isFaultReport(Status status, Status.Code code) {
        return Status.Code.INVALID_ARGUMENT != code && null != status.getCause();
    }

    /**
     * Upper bound on a logged description. Larger than {@link #MAX_ERROR_LENGTH} (which bounds what
     * reaches a CLIENT) because a log line can afford more context, but still a hard cap: part of
     * this string is attacker-controlled.
     */
    static final int MAX_LOGGED_DESCRIPTION = 200;

    /**
     * The description, appended to the bounded line so a refusal is not invisible on BOTH sides —
     * sanitized first, because part of it comes from the request.
     *
     * <p>For a code whose description the client already sees, logging it discloses nothing new.
     * For a code whose description is withheld, logging it is the entire reason withholding is
     * acceptable — this is where {@code JWKS not reachable at …} and {@code daily quota exceeded}
     * survive for an operator.
     *
     * <p><b>Why sanitizing is not optional here.</b> The auth path interpolates request-supplied
     * values into its descriptions: the {@code kid} from the token header, the rejected
     * {@code exp}/{@code nbf}, the client id. All of that is chosen by an UNAUTHENTICATED caller.
     * Written through verbatim it would let anyone who can reach the port forge log lines with an
     * embedded newline, inflate log volume with a megabyte of padding, or park arbitrary text in
     * our retention. "Bounded" has to mean bounded, not merely stackless — so control characters
     * go (no line forging) and the result is capped (no amplification).
     *
     * <p>None of those interpolated values is secret — a {@code kid} is a public key identifier,
     * {@code exp}/{@code nbf} are numbers, the client id is a header — and they carry real
     * diagnostic value, which is why they are cleaned rather than dropped. Audited against every
     * {@code withDescription} in {@code JwsVerify}: no token body, no signature bytes and no JWKS
     * key material reaches a description, so there is nothing here that must be suppressed
     * outright. {@code JwsVerify.malformed} already applies the same instinct, keeping raw token
     * bytes to a DEBUG line "so a flood of junk tokens cannot fill logs".
     */
    private static String loggableDescription(Status status, Status.Code code) {
        if (Status.Code.INVALID_ARGUMENT == code) {
            return "";
        }
        var description = status.getDescription();
        if (null == description || description.isBlank()) {
            return "";
        }
        return " : " + sanitizeForLog(description);
    }

    /**
     * Collapse every control character to a space and cap the length. Package-private for tests.
     *
     * <p>Control characters are removed rather than escaped: an escaped {@code \n} still lets a
     * reader's eye parse a forged "line", and nothing downstream needs the original bytes — the
     * point is that one log event stays one log line.
     */
    static String sanitizeForLog(String raw) {
        var cleaned = new StringBuilder(Math.min(raw.length(), MAX_LOGGED_DESCRIPTION));
        var lastWasSpace = false;
        for (var i = 0; i < raw.length() && cleaned.length() < MAX_LOGGED_DESCRIPTION; i++) {
            var c = raw.charAt(i);
            var isSpace = Character.isISOControl(c) || ' ' == c;
            if (isSpace) {
                if (!lastWasSpace && cleaned.length() > 0) {
                    cleaned.append(' ');
                }
                lastWasSpace = true;
                continue;
            }
            cleaned.append(c);
            lastWasSpace = false;
        }
        while (cleaned.length() > 0 && ' ' == cleaned.charAt(cleaned.length() - 1)) {
            cleaned.setLength(cleaned.length() - 1);
        }
        // Only mark truncation when input actually ran past the cap, so a description that merely
        // collapsed whitespace is not misreported as cut short.
        return raw.length() > MAX_LOGGED_DESCRIPTION ? cleaned + "..." : cleaned.toString();
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
