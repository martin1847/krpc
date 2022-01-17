package com.bt.rpc.server;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.validation.Validator;

import com.bt.rpc.common.AbstractContext;
import com.bt.rpc.common.FilterChain;
import com.bt.rpc.context.TraceMeta;
import com.bt.rpc.internal.InputProto;
import com.bt.rpc.server.jws.CredentialVerify;
import com.bt.rpc.server.jws.HttpConst;
import com.bt.rpc.server.jws.UserCredential;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.StatusException;
import org.slf4j.MDC;

import static com.bt.rpc.server.jws.HttpConst.BEARER_FLAG;

/**
 * 2020-04-07 13:38
 *
 * @author Martin.C
 */
public class ServerContext extends AbstractContext<ServerResult, InputProto, ServerContext> {

    static final ThreadLocal<ServerContext> LOCAL = new ThreadLocal<>();

    static final List<ServerFilter> GLOBAL_FILTERS = new ArrayList<>();

    public static final Key<String> CLIENT_ID     = Metadata.Key.of(HttpConst.CLIENT_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER);
    public static final Key<String> COOKIE        = Metadata.Key.of(HttpConst.COOKIE_HEADER, Metadata.ASCII_STRING_MARSHALLER);
    public static final Key<String> AUTHORIZATION = Metadata.Key.of(HttpConst.AUTHORIZATION_HEADER, Metadata.ASCII_STRING_MARSHALLER);

    static CredentialVerify credentialVerify;
    static Validator        validator;
    static String           applicationName;

    private static final Pattern COOKIE_SPLIT = Pattern.compile(";\\s*");

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
    private       boolean        isCookie        = false;

    public ServerContext(Class service, String method, Type resDto, InputProto arg,
                         FilterChain<ServerResult, ServerContext> lastChain, Metadata headers) {
        super(service, method, resDto, arg, lastChain);
        this.headers = headers;
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
        if (credentialVerify != null) {
            var tokenPlace = headers.get(AUTHORIZATION);
            String token = null;
            if (null != tokenPlace && tokenPlace.startsWith(BEARER_FLAG)) {
                token = tokenPlace.substring(BEARER_FLAG.length() + 1);
            } else if ((tokenPlace = headers.get(COOKIE)) != null) {
                var cookies = COOKIE_SPLIT.split(tokenPlace);
                final String cookiePrefix = credentialVerify.getCookieName() + '=';
                for (var ck : cookies) {
                    if (ck.startsWith(cookiePrefix)) {
                        token = ck.substring(cookiePrefix.length());
                        isCookie = true;
                        break;
                    }
                }
            }

            credential = credentialVerify.verify(token, clientId(), isCookie);
        }
    }

    public UserCredential getCredential() {
        return credential;
    }

    /**
     * JKS 中的用户id <Subject id
     * @return
     */
    public String uid(){
        return credential.getSubject();
    }

}
