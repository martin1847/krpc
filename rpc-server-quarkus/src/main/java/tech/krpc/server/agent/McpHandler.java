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
import tech.krpc.server.invoke.ValidationException;
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
        // AGENT-002 finding #5: name is the exposed app/service name (what RpcServerBuilder
        // stamped into the ApiMeta), so a multi-service agent can verify it connected to the
        // right server; version is the real krpc build version, never a hardcode.
        serverInfo.put("name", serverName());
        serverInfo.put("version", krpcVersion());
        var res = new LinkedHashMap<String, Object>();
        res.put("protocolVersion", negotiated);
        res.put("capabilities", caps);
        res.put("serverInfo", serverInfo);
        return result(id, res);
    }

    private Map<String, Object> toolsList(Object id) {
        var res = new LinkedHashMap<String, Object>();
        var tools = McpSchema.toolDefs(registry.apiMeta());
        res.put("tools", tools);
        // AGENT-002 finding #3b: an empty face is valid but silent — a real MCP client sees
        // "0 tools" and cannot tell "misconfigured" from "nothing exposed". The base MCP
        // Result carries an optional _meta; use it (initialize's `instructions` slot is not on
        // tools/list) to explain the empty set without inventing a non-spec field.
        if (tools.isEmpty()) {
            var meta = new LinkedHashMap<String, Object>();
            meta.put("tech.krpc/hint",
                    "0 tools: KRPC_MCP enabled but no @UnsafeWeb(agentTool=true) interfaces");
            res.put("_meta", meta);
        }
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
            // Unknown OR non-agentTool OR hidden: identical opaque error, no disclosure. But
            // AGENT-002 finding #3: a typo deserves a nudge, and an empty face deserves a hint.
            return errorResponse(id, INVALID_PARAMS, unknownToolMessage(toolName));
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
            // Credential failure / dispatch error -> MCP tool execution error (isError), not a
            // protocol error. AGENT-002 F1/F2/F3: surface the structured envelope (gRPC status
            // code + safe message + typed jakarta violations, no rejected value) instead of the
            // bare exception class name. Full detail is already logged server-side by the dispatch.
            log.warn("mcp tools/call {} failed: {}", toolName, ex.toString());
            return toolError(id, envelopeFromThrowable(ex));
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
            // Business failure (RpcResult.code != 0) -> MCP tool execution error carrying the
            // full {code,message} envelope (AGENT-002 finding #2), not a bare message string.
            var msg = output.getM();
            var envelope = new LinkedHashMap<String, Object>();
            envelope.put("code", code);
            envelope.put("message", (null != msg && !msg.isEmpty()) ? msg : ("code " + code));
            return toolError(id, envelope);
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

    /**
     * Tool execution error. The structured envelope {@code {code,message,violations?}} is
     * serialized as the JSON text of a single text content block (MCP spec 2025-06-18: tool
     * errors are unstructured {@code content} + {@code isError:true}; {@code structuredContent}
     * is reserved for outputSchema-conformant success data, so an error is NOT put there).
     */
    private Map<String, Object> toolError(Object id, Map<String, Object> envelope) {
        var content = new java.util.ArrayList<Map<String, Object>>();
        var text = new LinkedHashMap<String, Object>();
        text.put("type", "text");
        text.put("text", JsonUtils.stringify(envelope));
        content.add(text);
        var res = new LinkedHashMap<String, Object>();
        res.put("content", content);
        res.put("isError", true);
        return result(id, res);
    }

    /**
     * AGENT-002 F1/F3: build the error envelope from a thrown status. gRPC status code +
     * description are recovered via {@link io.grpc.Status#fromThrowable} (which walks the causal
     * chain). Violations come ONLY from the typed {@link ValidationException} channel — a
     * generic message plus {@code {field, constraint}} pairs, never a rejected value and never a
     * string reparse. Any other status is code + its own description, with NO violations key (so
     * business prose is never mistaken for a jakarta violation).
     */
    private static Map<String, Object> envelopeFromThrowable(Throwable ex) {
        io.grpc.Status status = io.grpc.Status.fromThrowable(ex);
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("code", status.getCode().value());

        ValidationException validation = findValidation(ex);
        if (null != validation) {
            // Secret-safe: generic message + typed {field, constraint} only (no rejected value).
            envelope.put("message", "Invalid input");
            var violations = new java.util.ArrayList<Map<String, Object>>();
            for (var v : validation.violations()) {
                var m = new LinkedHashMap<String, Object>();
                m.put("field", v.field());
                m.put("constraint", v.constraint());
                violations.add(m);
            }
            envelope.put("violations", violations);
            return envelope;
        }

        String desc = status.getDescription();
        envelope.put("message", (null != desc && !desc.isBlank()) ? desc : status.getCode().name());
        return envelope;
    }

    /** The typed validation carrier from the causal chain, or null if this is not one. */
    private static ValidationException findValidation(Throwable ex) {
        for (Throwable t = ex; null != t; t = t.getCause()) {
            if (t instanceof ValidationException ve) {
                return ve;
            }
        }
        return null;
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

    // --- serverInfo (AGENT-002 finding #5) ----------------------------------------------

    /**
     * The exposed application/service name — {@code RpcServerBuilder} stamps it into the
     * live {@link tech.krpc.common.meta.ApiMeta#getApp()}. Falls back to {@code "krpc"} only
     * when the surface has not been initialised (e.g. a bare unit handler).
     */
    private String serverName() {
        var meta = registry.apiMeta();
        if (null != meta && null != meta.getApp() && !meta.getApp().isBlank()) {
            return meta.getApp();
        }
        return "krpc";
    }

    /**
     * The real krpc build version, read from the runtime jar's {@code Implementation-Version}
     * manifest attribute (populated by the Gradle build), never hardcoded here. Falls back to
     * {@link tech.krpc.common.RpcConstants#VERSION} when the manifest is absent (unit tests,
     * exploded classpaths, and native images that drop package metadata).
     */
    static String krpcVersion() {
        var v = McpHandler.class.getPackage().getImplementationVersion();
        return (null != v && !v.isBlank()) ? v : tech.krpc.common.RpcConstants.VERSION;
    }

    // --- did-you-mean (AGENT-002 finding #3) --------------------------------------------

    /**
     * Unknown-tool message: append the nearest known tool name(s) (edit distance ≤ 2, max 3)
     * as a did-you-mean nudge, or an explicit "0 tools" hint when the face is empty. No
     * disclosure beyond names already returned by {@code tools/list}.
     */
    private String unknownToolMessage(String toolName) {
        var names = registry.toolNames();
        if (names.isEmpty()) {
            return "Unknown tool: " + toolName
                    + " (0 tools registered: KRPC_MCP enabled but no @UnsafeWeb(agentTool=true) interfaces)";
        }
        var suggestions = suggest(toolName, names);
        if (suggestions.isEmpty()) {
            return "Unknown tool: " + toolName;
        }
        return "Unknown tool: " + toolName + ". Did you mean: " + String.join(", ", suggestions) + "?";
    }

    /** Names within edit distance 2 of {@code toolName}, nearest first, at most 3. */
    static List<String> suggest(String toolName, java.util.Collection<String> names) {
        record Cand(String name, int dist) {}
        var cands = new java.util.ArrayList<Cand>();
        for (String n : names) {
            int d = editDistance(toolName, n);
            if (d <= 2) {
                cands.add(new Cand(n, d));
            }
        }
        cands.sort(java.util.Comparator.comparingInt(Cand::dist).thenComparing(Cand::name));
        var out = new java.util.ArrayList<String>();
        for (int i = 0; i < cands.size() && i < 3; i++) {
            out.add(cands.get(i).name());
        }
        return out;
    }

    /** Iterative Levenshtein with a rolling row — bounded by the (short) tool-name lengths. */
    private static int editDistance(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (0 == n) {
            return m;
        }
        if (0 == m) {
            return n;
        }
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
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
