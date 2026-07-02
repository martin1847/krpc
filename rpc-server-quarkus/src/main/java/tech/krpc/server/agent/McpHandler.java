package tech.krpc.server.agent;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.grpc.Metadata;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AsciiString;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
import tech.krpc.util.EnvUtils;
import tech.krpc.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * ADR-0004 (AGENT-001 P1): {@code POST /mcp} — a hand-written MCP Streamable HTTP endpoint
 * (JSON-RPC 2.0, MCP spec 2025-06-18), a thin bridge over the P0 agent surface.
 *
 * <p>Supports {@code initialize}, {@code notifications/initialized}, {@code tools/list},
 * {@code tools/call}, {@code ping}. Tools are generated from the live agentTool
 * {@link ApiMeta} ({@link McpSchema}); {@code tools/call} dispatches through the exact same
 * {@link WebInvoker#invokeWeb} path as {@code /agent/invoke}, so the credential check is
 * never bypassed. Only {@code @UnsafeWeb(agentTool=true)} methods are reachable
 * ({@link McpToolRegistry}); hidden services are already double-filtered upstream.
 *
 * <p>Transport: JSON-response mode only (a single {@code application/json} object per POST).
 * SSE is spec-optional and not used — krpc tools are unary request/response. Gated by
 * {@code rpc.server.mcp.enabled} (env {@code KRPC_MCP}), default OFF = the path is never
 * registered ({@link #enabled()}), i.e. byte-level zero new surface.
 */
@Unremovable
@ApplicationScoped
@Slf4j
public class McpHandler implements PostHandler<String> {

    /// Latest MCP protocol version this bridge implements.
    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final List<String> SUPPORTED_VERSIONS =
            List.of("2025-06-18", "2025-03-26", "2024-11-05");

    /// Streamable HTTP protocol-version header (spec 2025-06-18).
    static final String MCP_PROTOCOL_VERSION_HEADER = "mcp-protocol-version";

    private static final AsciiString STATUS_HEADER =
            AsciiString.cached(AbstractHttpHandler.STATUS_OVERRIDE_HEADER);

    // JSON-RPC 2.0 error codes.
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of(HttpConst.AUTHORIZATION_HEADER, Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> CLIENT_ID =
            Metadata.Key.of(HttpConst.CLIENT_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER);

    @ConfigProperty(name = "rpc.server.mcp.enabled", defaultValue = "false")
    boolean mcpEnabled;

    @Inject
    McpToolRegistry registry;

    @Override
    public String path() {
        return "/mcp";
    }

    @Override
    public String contextType() {
        return AbstractHttpHandler.TYPE_JSON;
    }

    // Raw JSON-RPC body; the target method validates its own typed DTO inside dispatch.
    @Override
    public boolean useValidator() {
        return false;
    }

    @Override
    public Class<String> getParamClass() {
        return String.class;
    }

    /**
     * Flag gate. Honours both {@code rpc.server.mcp.enabled} and the documented env
     * {@code KRPC_MCP} (so the switch works without a properties file). Default OFF.
     */
    @Override
    public boolean enabled() {
        if (mcpEnabled) {
            return true;
        }
        var env = EnvUtils.env("KRPC_MCP", "false");
        return "true".equalsIgnoreCase(env) || "1".equals(env);
    }

    @Override
    public byte[] handle(String body, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
        Object parsed;
        try {
            parsed = JsonUtils.parse(body, Object.class);
        } catch (RuntimeException ex) {
            return bytes(errorResponse(null, PARSE_ERROR, "Parse error"));
        }
        if (!(parsed instanceof Map)) {
            // Batches were removed in 2025-06-18; a non-object is an invalid request.
            return bytes(errorResponse(null, INVALID_REQUEST, "Invalid Request"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>) parsed;

        Object id = msg.get("id");
        Object methodObj = msg.get("method");
        if (!(methodObj instanceof String method)) {
            return bytes(errorResponse(id, INVALID_REQUEST, "Invalid Request: missing method"));
        }

        // Transport (spec 2025-06-18): the client MUST send MCP-Protocol-Version on every
        // request after initialize; an invalid/unsupported value MUST be 400. initialize is
        // exempt (it negotiates via the body). Absent header -> assume default, don't fail.
        if (!"initialize".equals(method)) {
            var pv = requestHeaders.get(MCP_PROTOCOL_VERSION_HEADER);
            if (null != pv && !SUPPORTED_VERSIONS.contains(pv)) {
                badRequest(resHeader);
                return bytes(errorResponse(id, INVALID_REQUEST, "Unsupported MCP-Protocol-Version: " + pv));
            }
        }

        // Notifications (no id) get 202 Accepted with an empty body, per Streamable HTTP.
        if (null == id) {
            accepted(resHeader);
            return AsciiHeader_EMPTY;
        }

        switch (method) {
            case "initialize":
                return bytes(initialize(id, msg));
            case "tools/list":
                return bytes(toolsList(id));
            case "tools/call":
                return bytes(toolsCall(id, msg, requestHeaders));
            case "ping":
                return bytes(result(id, new LinkedHashMap<>()));
            default:
                return bytes(errorResponse(id, METHOD_NOT_FOUND, "Method not found: " + method));
        }
    }

    // --- MCP methods --------------------------------------------------------------------

    private Map<String, Object> initialize(Object id, Map<String, Object> msg) {
        String negotiated = PROTOCOL_VERSION;
        Object params = msg.get("params");
        if (params instanceof Map<?, ?> p) {
            var requested = p.get("protocolVersion");
            if (requested instanceof String rv && SUPPORTED_VERSIONS.contains(rv)) {
                negotiated = rv;
            }
        }
        var caps = new LinkedHashMap<String, Object>();
        caps.put("tools", new LinkedHashMap<>()); // listChanged omitted: tool set is static per boot
        var serverInfo = new LinkedHashMap<String, Object>();
        serverInfo.put("name", "krpc");
        serverInfo.put("version", tech.krpc.common.RpcConstants.VERSION);
        var res = new LinkedHashMap<String, Object>();
        res.put("protocolVersion", negotiated);
        res.put("capabilities", caps);
        res.put("serverInfo", serverInfo);
        return result(id, res);
    }

    private Map<String, Object> toolsList(Object id) {
        var res = new LinkedHashMap<String, Object>();
        res.put("tools", McpSchema.toolDefs(registry.apiMeta()));
        return result(id, res);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolsCall(Object id, Map<String, Object> msg, HttpHeaders requestHeaders) {
        Object paramsObj = msg.get("params");
        if (!(paramsObj instanceof Map)) {
            return errorResponse(id, INVALID_PARAMS, "Invalid params");
        }
        Map<String, Object> params = (Map<String, Object>) paramsObj;
        var nameObj = params.get("name");
        if (!(nameObj instanceof String toolName)) {
            return errorResponse(id, INVALID_PARAMS, "Invalid params: name required");
        }

        WebInvoker invoker = registry.lookup(toolName);
        if (null == invoker) {
            // Unknown OR non-agentTool OR hidden: identical opaque error, no disclosure.
            return errorResponse(id, INVALID_PARAMS, "Unknown tool: " + toolName);
        }

        // Build the krpc JSON input from the MCP arguments object. The arg-shape rule
        // mirrors McpSchema exactly: object-like arg -> forward verbatim; wrapped scalar
        // -> unwrap arguments.value.
        Object argsObj = params.get("arguments");
        Object krpcInput = resolveInput(toolName, argsObj);
        var input = InputProto.newBuilder().setE(SerialEnum.JSON);
        if (null != krpcInput) {
            input.setUtf8(JsonUtils.stringify(krpcInput));
        }

        var headers = toMetadata(requestHeaders);
        try {
            ServerResult sr = invoker.invokeWeb(input.build(), headers);
            return toolResult(id, sr.output);
        } catch (Throwable ex) {
            // Credential failure / dispatch error: MCP tool execution error (isError), not
            // a protocol error. Message kept generic; detail already logged server-side.
            log.warn("mcp tools/call {} failed: {}", toolName, ex.toString());
            return toolError(id, ex.getClass().getSimpleName());
        }
    }

    /**
     * Whether the tool's arg is object-like (forward verbatim) vs wrapped-scalar (unwrap
     * {@code arguments.value}). Recomputed from the live ApiMeta with the same rule
     * {@link McpSchema#argIsObject} uses at tools/list time — single source of truth.
     */
    private Object resolveInput(String toolName, Object argsObj) {
        var arg = argTypeOf(toolName);
        boolean objectLike = McpSchema.argIsObject(arg);
        if (objectLike) {
            return argsObj; // may be null (no-arg) or the arguments map (DTO/Map)
        }
        if (argsObj instanceof Map<?, ?> m) {
            return m.get(McpSchema.WRAP_KEY);
        }
        return null;
    }

    private tech.krpc.common.meta.PropertyType argTypeOf(String toolName) {
        var meta = registry.apiMeta();
        if (null == meta || null == meta.getApis()) {
            return null;
        }
        for (var api : meta.getApis()) {
            if (null == api.getMethods()) {
                continue;
            }
            for (var m : api.getMethods()) {
                if (toolName.equals(McpToolRegistry.toolName(api.getName(), m.getName()))) {
                    return m.getArg();
                }
            }
        }
        return null;
    }

    // --- result shaping -----------------------------------------------------------------

    private Map<String, Object> toolResult(Object id, OutputProto output) {
        int code = output.getC();
        String dataJson = output.hasUtf8() ? output.getUtf8() : null;

        if (code != 0) {
            // Business failure (RpcResult.code != 0) -> MCP tool execution error.
            var msg = output.getM();
            return toolError(id, (null != msg && !msg.isEmpty()) ? msg : ("code " + code));
        }

        var content = new java.util.ArrayList<Map<String, Object>>();
        var text = new LinkedHashMap<String, Object>();
        text.put("type", "text");
        text.put("text", null != dataJson ? dataJson : "null");
        content.add(text);

        var res = new LinkedHashMap<String, Object>();
        res.put("content", content);
        res.put("isError", false);
        // structuredContent MUST be an object; include it only when data is a JSON object.
        Object structured = parseStructured(dataJson);
        if (structured instanceof Map) {
            res.put("structuredContent", structured);
        }
        return result(id, res);
    }

    private Map<String, Object> toolError(Object id, String message) {
        var content = new java.util.ArrayList<Map<String, Object>>();
        var text = new LinkedHashMap<String, Object>();
        text.put("type", "text");
        text.put("text", message);
        content.add(text);
        var res = new LinkedHashMap<String, Object>();
        res.put("content", content);
        res.put("isError", true);
        return result(id, res);
    }

    private static Object parseStructured(String dataJson) {
        if (null == dataJson || dataJson.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.parse(dataJson, Object.class);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // --- JSON-RPC envelope --------------------------------------------------------------

    private static Map<String, Object> result(Object id, Object result) {
        var env = new LinkedHashMap<String, Object>();
        env.put("jsonrpc", "2.0");
        env.put("id", id);
        env.put("result", result);
        return env;
    }

    private static Map<String, Object> errorResponse(Object id, int code, String message) {
        var err = new LinkedHashMap<String, Object>();
        err.put("code", code);
        err.put("message", message);
        var env = new LinkedHashMap<String, Object>();
        env.put("jsonrpc", "2.0");
        env.put("id", id); // null id is valid for pre-dispatch errors
        env.put("error", err);
        return env;
    }

    // --- headers / transport ------------------------------------------------------------

    private static void accepted(List<AsciiHeader> resHeader) {
        resHeader.add(new AsciiHeader(STATUS_HEADER, "202"));
    }

    private static void badRequest(List<AsciiHeader> resHeader) {
        resHeader.add(new AsciiHeader(STATUS_HEADER, "400"));
    }

    Metadata toMetadata(HttpHeaders requestHeaders) {
        var headers = new Metadata();
        copy(requestHeaders, headers, HttpConst.AUTHORIZATION_HEADER, AUTHORIZATION);
        copy(requestHeaders, headers, HttpConst.CLIENT_ID_HEADER, CLIENT_ID);
        // ADR-0003: propagate inbound W3C trace context if the client supplied it.
        copy(requestHeaders, headers, TraceMeta.TRACEPARENT, TraceMeta.TRACEPARENT_KEY);
        return headers;
    }

    private static void copy(HttpHeaders from, Metadata to, String name, Metadata.Key<String> key) {
        var val = from.get(name);
        if (null != val) {
            to.put(key, val);
        }
    }

    private static final byte[] AsciiHeader_EMPTY = new byte[0];

    private static byte[] bytes(Map<String, Object> json) {
        return JsonUtils.stringify(json).getBytes(StandardCharsets.UTF_8);
    }
}
