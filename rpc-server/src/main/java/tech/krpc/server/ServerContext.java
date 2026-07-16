package tech.krpc.server;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Validator;

import tech.krpc.common.AbstractContext;
import tech.krpc.common.FilterChain;
import tech.krpc.context.TraceMeta;
import tech.krpc.internal.InputProto;
import tech.krpc.server.jws.CredentialVerify;
import tech.krpc.server.jws.HttpConst;
import tech.krpc.server.jws.UserCredential;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.StatusException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/**
 * 2020-04-07 13:38
 *
 * @author Martin.C
 */
@Slf4j
public class ServerContext extends AbstractContext<ServerResult, InputProto, ServerContext> {

    // ADR: per-request ServerContext rides on io.grpc.Context (the call-scoped context
    // gRPC auto-attaches on its executor) instead of a bare ThreadLocal, so it stays
    // correct across virtual-thread executors without wrapping.
    static final io.grpc.Context.Key<ServerContext> SC_KEY = io.grpc.Context.key("krpc-server-context");

    static final List<ServerFilter> GLOBAL_FILTERS = new ArrayList<>();

    public static final Key<String> CLIENT_ID     = Metadata.Key.of(HttpConst.CLIENT_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER);

    static CredentialVerify credentialVerify;
    static Validator        validator;
    static String           applicationName;



    public static void regGlobalFilter(ServerFilter filter) {
        GLOBAL_FILTERS.add(filter);
    }

    public static void regValidator(Validator validator) {
        ServerContext.validator = validator;
    }

    public static void regCredentialVerify(CredentialVerify credentialVerify) {
        ServerContext.credentialVerify = credentialVerify;
    }

    // HARDEN-B1: read-only accessor for the registered verifier. Lets the security regression
    // tests assert the fail-closed contract (verifier registered + rejecting) without reflecting
    // on a package-private field from another module.
    public static CredentialVerify credentialVerify() {
        return credentialVerify;
    }

    public static ServerContext current() {
        return SC_KEY.get();
    }

    public static String applicationName() {return applicationName;}

    private static boolean injectMdc(Metadata headers,String keyStr,Key<String> key){
        var val = headers.get(key);
        if(null != val) {
            MDC.put(keyStr, val);
            return true;
        }
        return false;
    }
    //
    //public static Context grpcContext() {
    //    return Context.current();
    //}


    //--------------- static over --------------------//

    private       Metadata       headers;
    private final Metadata       responseHeaders = new Metadata();
    private       UserCredential credential;
    private       boolean        verifyed        = false;

    public ServerContext(Class service, String method, Type resDto, InputProto arg,
                         FilterChain<ServerResult, ServerContext> lastChain, Metadata headers) {
        super(service, method, resDto, arg, lastChain);
        this.headers = headers;
        injectMdc(headers, HttpConst.CLIENT_ID_HEADER,CLIENT_ID);
        // ADR-0003 / OTEL-002 R2-2: validate the inbound W3C traceparent BEFORE writing any
        // trace-related MDC. An invalid header sets none of MDC_TRACEPARENT / traceId / spanId /
        // tracestate / x-request-id, so the no-SDK client path forwards zero traceparent downstream
        // rather than an incoherent one — matching the HTTP face. A valid future-version header
        // (with opaque trailing fields) is bound and forwarded verbatim.
        var traceparent = headers.get(TraceMeta.TRACEPARENT_KEY);
        var ids = TraceMeta.parse(traceparent);
        if(null != ids){
            MDC.put(TraceMeta.MDC_TRACEPARENT, traceparent);
            MDC.put(TraceMeta.MDC_TRACE_ID, ids[0]);
            MDC.put(TraceMeta.MDC_SPAN_ID, ids[1]);
            injectMdc(headers, TraceMeta.TRACESTATE,TraceMeta.TRACESTATE_KEY);
            injectMdc(headers, TraceMeta.X_REQUEST_ID,TraceMeta.REQUEST_ID);
        }
    }



    public Metadata getHeaders() {
        return headers;
    }

    public String logTrace() {
        return ":" + headers.get(TraceMeta.TRACEPARENT_KEY);
    }

    public Metadata getResponseHeaders() {
        return responseHeaders;
    }

    public String clientId() {
        return headers.get(CLIENT_ID);
    }

    void checkCredential() throws StatusException {
        if(verifyed){
            return;
        }
        // AUD-omp-13: set verifyed=true ONLY AFTER verify() returns cleanly. Pre-fix it was set
        // BEFORE verify(), so a thrown verify() left verifyed=true + credential=null — a later
        // softUid() then short-circuited (verifyed) and uid() NPE'd (500) on the null credential.
        // On failure verifyed stays false: the StatusException propagates (fail-closed) and a retry
        // re-verifies rather than silently trusting a half-done attempt.
        if (credentialVerify != null) {
            var token = CredentialVerify.bearerToken(headers);
            var isCookie = false;
            if( null == token){
                token = credentialVerify.cookieToken(headers);
                isCookie = true;
            }
            credential = credentialVerify.verify(token, clientId(), isCookie);
        }
        verifyed = true;
    }

    public UserCredential getCredential() {
        return credential;
    }

    /**
     * 登录了获取用户id，没登录也可以，不会报错
     */
    public String softUid() {
        if (!verifyed) {
            try {
                checkCredential();
            } catch (StatusException e) {
                // ignore the error
                log.debug("ignore soft check Credential Exception {}", e.getMessage());
            }
        }
        if (credential != null) {
            return credential.getSubject();
        }
        return null;
    }

    /**
     * JKS 中的用户id <Subject id
     * @return
     */
    public String uid(){
        // AUD-omp-13: clean rejection instead of an NPE. credential is null when the call is not
        // credential-required (checkCredential never ran / verify skipped) yet the impl calls uid().
        // That is a caller/config error — surface it explicitly, not as an opaque NullPointerException.
        if (credential == null) {
            throw new IllegalStateException(
                    "uid() requires an authenticated request — no credential resolved "
                    + "(is the service @UnsafeWeb(requireCredential=true) / @RequireCredential?). Use softUid() when auth is optional.");
        }
        return credential.getSubject();
    }

}
