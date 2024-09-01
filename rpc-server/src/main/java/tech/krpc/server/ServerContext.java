package tech.krpc.server;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import io.netty.util.concurrent.FastThreadLocal;
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

    static final FastThreadLocal<ServerContext> LOCAL = new FastThreadLocal<>();

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

    public static ServerContext current() {
        return LOCAL.get();
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
        if(injectMdc(headers, TraceMeta.X_B3_TRACE_ID,TraceMeta.TRACE_ID)){
            injectMdc(headers, TraceMeta.X_B3_SPAN_ID,TraceMeta.SPAN_ID);
            injectMdc(headers, TraceMeta.X_B3_PARENT_SPAN_ID,TraceMeta.PARENT_SPAN_ID);
            injectMdc(headers, TraceMeta.X_REQUEST_ID,TraceMeta.REQUEST_ID);

            injectMdc(headers, TraceMeta.X_B3_SAMPLED,TraceMeta.SAMPLED);
            injectMdc(headers, TraceMeta.X_B3_DEBUG_FLAG,TraceMeta.DEBUG_FLAG);
        }
    }



    public Metadata getHeaders() {
        return headers;
    }

    public String logTrace() {
        return ":" + headers.get(TraceMeta.TRACE_ID) + ":" + headers.get(TraceMeta.SPAN_ID);
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
        verifyed = true;
        if (credentialVerify != null) {
            var token = CredentialVerify.bearerToken(headers);
            var isCookie = false;
            if( null == token){
                token = credentialVerify.cookieToken(headers);
                isCookie = true;
            }
            credential = credentialVerify.verify(token, clientId(), isCookie);
        }
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
        return credential.getSubject();
    }

}
