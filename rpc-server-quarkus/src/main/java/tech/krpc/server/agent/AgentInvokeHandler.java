package tech.krpc.server.agent;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.StatusException;
import io.grpc.Status;
import io.netty.handler.codec.http.HttpHeaders;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.krpc.context.TraceMeta;
import tech.krpc.http.server.AbstractHttpHandler;
import tech.krpc.http.server.AsciiHeader;
import tech.krpc.http.server.PostHandler;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;
import tech.krpc.internal.SerialEnum;
import tech.krpc.server.ServerResult;
import tech.krpc.server.WebInvoker;
import tech.krpc.server.jws.HttpConst;
import tech.krpc.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * ADR-0004 (AGENT-001 P0): {@code POST /agent/invoke}.
 *
 * <p>The HTTP analogue of {@code GeneralizeClient}: resolve {@code app/Service/method},
 * forward the request {@code input} JSON verbatim, and dispatch through the exact same
 * credential + filter-chain path as gRPC ({@link UnaryMethod#invokeWeb}). Because lookup
 * goes through {@link WebMethodRegistry}, only {@code @UnsafeWeb} services are reachable;
 * unknown or hidden services resolve to {@code null} and are rejected.
 *
 * <p>Errors are reported in the JSON body via a {@code code} field (gRPC-style status,
 * mirroring {@code OutputProto}/{@code RpcResult}), not via the HTTP status line — the
 * underlying netty transport only emits 200 (handled), 404 (unknown path) or 500
 * (uncaught exception). Not-found/forbidden therefore surface as {@code code:5}
 * — gRPC {@code NOT_FOUND} ({@link #CODE_NOT_FOUND}), never HTTP {@code 404}.
 */
// AGENT-001 P1 prerequisite: this handler is discovered reflectively by
// HttpHandlerExpose (getBeans(Object,Any)), so Arc's default remove-unused-beans=all
// would strip it as unused, making /agent/invoke absent in a default consumer.
@Unremovable
@ApplicationScoped
@Slf4j
public class AgentInvokeHandler implements PostHandler<AgentInvokeRequest> {

    // gRPC Code.NOT_FOUND == 5; reused for unknown/hidden service.
    static final int CODE_NOT_FOUND = 5;
    static final int CODE_INTERNAL  = 13;

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of(HttpConst.AUTHORIZATION_HEADER, Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> CLIENT_ID =
            Metadata.Key.of(HttpConst.CLIENT_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER);

    @Inject
    WebMethodRegistry registry;

    @Override
    public String path() {
        return "/agent/invoke";
    }

    @Override
    public String contextType() {
        return AbstractHttpHandler.TYPE_JSON;
    }

    // AgentInvokeRequest carries raw-JSON input; skip bean validation here (the target
    // method validates its own typed DTO inside the dispatch path).
    @Override
    public boolean useValidator() {
        return false;
    }

    // CDI proxies break AbstractPostHandler's generic-superclass reflection, so the param
    // class is supplied explicitly instead of extending AbstractPostHandler.
    @Override
    public Class<AgentInvokeRequest> getParamClass() {
        return AgentInvokeRequest.class;
    }

    @Override
    public byte[] handle(AgentInvokeRequest req, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
        if (null == req || null == req.getService() || null == req.getMethod()) {
            return error(CODE_NOT_FOUND, "service and method are required");
        }

        WebInvoker method = registry.lookup(req.getService(), req.getMethod());
        if (null == method) {
            // Unknown OR hidden service: identical opaque response, no internal disclosure.
            return error(CODE_NOT_FOUND, req.getService() + "/" + req.getMethod() + " not found");
        }

        var input = InputProto.newBuilder().setE(SerialEnum.JSON);
        var inputValue = req.getInput();
        if (null != inputValue) {
            input.setUtf8(JsonUtils.stringify(inputValue));
        }

        var headers = toMetadata(requestHeaders);

        try {
            ServerResult result = method.invokeWeb(input.build(), headers);
            OutputProto output = result.output;
            return outputToJson(output).getBytes(StandardCharsets.UTF_8);
        } catch (Throwable ex) {
            // Credential failure (StatusException) and method errors land here. Keep the
            // client message generic; the dispatch path already logged detail server-side.
            // One line, no stack: UnaryMethod.invokeWeb already logged this exception at ERROR
            // with its full cause chain. What that log cannot say is WHICH service/method was
            // called, so this adds only the routing context and the exception's identity.
            log.warn("agent invoke {}/{} failed: {}", req.getService(), req.getMethod(),
                    ex.getClass().getSimpleName());
            // AGENT-ERRCODE: derive the code from the Status that UnaryMethod.toClientError already
            // attached, instead of reporting a blanket INTERNAL. This face used to answer 13 for a
            // malformed body and 13 for a validation failure -- both client errors dressed as
            // server faults, and both indistinguishable from a real crash. CODE_INTERNAL stays as
            // the last resort for a throwable that carries no Status at all, which invokeWeb's
            // mapping should make unreachable.
            if (ex instanceof StatusException || ex instanceof StatusRuntimeException) {
                var status = Status.fromThrowable(ex);
                var description = status.getDescription();
                return error(status.getCode().value(),
                        null != description ? description : ex.getClass().getSimpleName());
            }
            return error(CODE_INTERNAL, ex.getClass().getSimpleName());
        }
    }

    // AGENT-001 P1 extension point: an agentTool visibility gate (@UnsafeWeb(agentTool))
    // would filter this registry / discovery further; P0 gates on web (@UnsafeWeb) only.

    Metadata toMetadata(HttpHeaders requestHeaders) {
        var headers = new Metadata();
        copy(requestHeaders, headers, HttpConst.AUTHORIZATION_HEADER, AUTHORIZATION);
        copy(requestHeaders, headers, HttpConst.CLIENT_ID_HEADER, CLIENT_ID);
        // ADR-0003: propagate inbound W3C trace context if the agent supplied it.
        copy(requestHeaders, headers, TraceMeta.TRACEPARENT, TraceMeta.TRACEPARENT_KEY);
        return headers;
    }

    private static void copy(HttpHeaders from, Metadata to, String name, Metadata.Key<String> key) {
        var val = from.get(name);
        if (null != val) {
            to.put(key, val);
        }
    }

    private static byte[] error(int code, String message) {
        var body = "{\"code\":" + code + ",\"message\":" + JsonUtils.stringify(message) + "}";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    // Mirror of GeneralizeClient.toJson (rpc-client), inlined to avoid a client dependency
    // in a server module. OutputProto -> {code,message?,data}.
    static String outputToJson(OutputProto output) {
        var sb = new StringBuilder(200);
        sb.append("{\"code\":").append(output.getC());
        var message = output.getM();
        if (null != message && message.length() > 0) {
            sb.append(",\"message\":").append(JsonUtils.stringify(message));
        }
        if (output.hasUtf8()) {
            sb.append(",\"data\":").append(output.getUtf8());
        } else if (output.hasBs()) {
            sb.append(",\"data\":").append(JsonUtils.stringify(
                    java.util.Base64.getEncoder().encodeToString(output.getBs())));
        }
        sb.append('}');
        return sb.toString();
    }
}
