package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.Status;
import io.grpc.StatusException;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import tech.krpc.common.meta.Anno;
import tech.krpc.common.meta.Api;
import tech.krpc.common.meta.ApiMeta;
import tech.krpc.common.meta.Dto;
import tech.krpc.common.meta.Method;
import tech.krpc.common.meta.Property;
import tech.krpc.common.meta.PropertyType;
import tech.krpc.http.server.AsciiHeader;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;
import tech.krpc.server.ServerResult;
import tech.krpc.server.WebInvoker;
import tech.krpc.util.JsonUtils;

/**
 * ADR-0004 (AGENT-001 P1): McpHandler unit tests — plain JUnit5, no Quarkus boot.
 *
 * <p>Drives {@link McpHandler#handle} directly (the {@code enabled()} gate is
 * HttpHandlerExpose's job, not handle()'s), asserting the JSON-RPC / MCP contracts and,
 * above all, the three security red lines: the credential check is enforced (not bypassed)
 * on tools/call, only agentTool methods are reachable, and a business failure is a tool
 * error rather than a silent success.
 */
class McpHandlerTest {

    // --- fixtures -----------------------------------------------------------------------

    /**
     * ApiMeta for a single agentTool service {@code Calc} with two methods:
     * {@code add(AddReq)} — a DTO arg whose {@code name} field carries {@code @NotBlank}
     * (=> required + minLength:1) and {@code @Doc} (=> description); and {@code ping()} — a
     * no-arg method. This is the exact {@code Anno} shape RpcMetaServiceImpl.toAnno emits.
     */
    private static ApiMeta calcApiMeta() {
        var nameField = new Property("name", scalar("String"),
                List.of(new Anno("NotBlank", Map.of()),
                        new Anno("Doc", Map.of("value", "the person name"))));
        var addReq = new Dto("AddReq", 0, true, null);
        addReq.setFields(List.of(nameField));

        var add = new Method();
        add.setName("add");
        add.setArg(new PropertyType(addReq));
        // res left null: description falls back to Api.method, outputSchema omitted.

        var ping = new Method();
        ping.setName("ping");
        // arg left null: no-arg tool.

        var api = new Api();
        api.setName("Calc");
        api.setMethods(List.of(add, ping));

        return new ApiMeta("test-app", List.of(api), List.of());
    }

    private static PropertyType scalar(String typeName) {
        return new PropertyType(new Dto(typeName, 0, false, null));
    }

    /** Handler wired with the shared Calc ApiMeta and the given "Service/method" fakes. */
    private static McpHandler handler(Map<String, WebInvoker> webKeyed) {
        var registry = new McpToolRegistry();
        registry.init(webKeyed, calcApiMeta());
        var handler = new McpHandler();
        handler.registry = registry;
        return handler;
    }

    private static ServerResult ok(String utf8) {
        return new ServerResult(OutputProto.newBuilder().setC(0).setUtf8(utf8).build());
    }

    /** A WebInvoker that ignores its input and always returns {@code ok(utf8)}. */
    private static WebInvoker okInv(String utf8) {
        return (in, md) -> ok(utf8);
    }

    // --- invocation helpers -------------------------------------------------------------

    /** Parse the JSON-RPC envelope handle() returns (null when the body is empty). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(McpHandler h, String body, HttpHeaders req,
                                            List<AsciiHeader> resHeaders) {
        byte[] out = h.handle(body, resHeaders, req);
        if (0 == out.length) {
            return null;
        }
        var parsed = JsonUtils.parse(new String(out, StandardCharsets.UTF_8), Object.class);
        return (Map<String, Object>) parsed;
    }

    private static Map<String, Object> call(McpHandler h, String body) {
        return call(h, body, new DefaultHttpHeaders(), new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> env) {
        var r = env.get("result");
        assertInstanceOf(Map.class, r, "expected a JSON-RPC result, got: " + env);
        return (Map<String, Object>) r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> env) {
        var e = env.get("error");
        assertInstanceOf(Map.class, e, "expected a JSON-RPC error, got: " + env);
        return (Map<String, Object>) e;
    }

    private static int errorCode(Map<String, Object> env) {
        return ((Number) error(env).get("code")).intValue();
    }

    @SuppressWarnings("unchecked")
    private static boolean isError(Map<String, Object> env) {
        return Boolean.TRUE.equals(result(env).get("isError"));
    }

    @SuppressWarnings("unchecked")
    private static String firstText(Map<String, Object> env) {
        var content = (List<Map<String, Object>>) result(env).get("content");
        assertFalse(content.isEmpty(), "content block empty: " + env);
        return (String) content.get(0).get("text");
    }

    private static String toolsCall(String toolName, String argumentsJson) {
        var args = null == argumentsJson ? "" : ",\"arguments\":" + argumentsJson;
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + toolName + "\"" + args + "}}";
    }

    // --- initialize ---------------------------------------------------------------------

    @Test
    void initialize_negotiatesVersionAndAnnouncesToolsAndServerInfo() {
        // requested -> echoed; unknown -> falls back to the legacy ceiling. 2026-07-28 REMOVED
        // initialize, so it is never the negotiated outcome of initialize either.
        record Case(String requested, String expected) {}
        var cases = List.of(
                new Case("2025-11-25", "2025-11-25"),
                new Case("2025-06-18", "2025-06-18"),
                new Case("2025-03-26", "2025-03-26"),
                new Case("2026-07-28", McpHandler.INITIALIZE_MAX_VERSION),
                new Case("1999-01-01", McpHandler.INITIALIZE_MAX_VERSION));

        var h = handler(Map.of());
        for (var c : cases) {
            var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"" + c.requested() + "\"}}";
            var res = result(call(h, body));
            assertEquals(c.expected(), res.get("protocolVersion"),
                    "protocolVersion negotiation for " + c.requested());

            var caps = res.get("capabilities");
            assertInstanceOf(Map.class, caps, "capabilities present");
            assertTrue(((Map<?, ?>) caps).containsKey("tools"), "capabilities.tools present");

            var info = res.get("serverInfo");
            assertInstanceOf(Map.class, info, "serverInfo present");
            // AGENT-002 finding #5: name is the exposed app name (ApiMeta.app), not "krpc".
            assertEquals("test-app", ((Map<?, ?>) info).get("name"), "serverInfo.name = app name");
            // version is sourced from krpcVersion() (jar manifest, fallback RpcConstants.VERSION),
            // never the old "1.0.0" literal in the handler.
            assertEquals(McpHandler.krpcVersion(), ((Map<?, ?>) info).get("version"),
                    "serverInfo.version = resolved krpc build version, not a hardcode");
        }
    }

    @Test
    void krpcVersion_fallbackIsGeneratedProjectVersion_notStaleHardcode() {
        // AGENT-002 F2: on the exploded test classpath the module manifest is absent, so
        // krpcVersion() takes the fallback — which is now the generated project version (single
        // source of truth), never a hand-maintained stale constant.
        assertEquals(tech.krpc.common.BuildVersion.VERSION, McpHandler.krpcVersion(),
                "krpcVersion fallback = generated build version");
        assertEquals(tech.krpc.common.BuildVersion.VERSION, tech.krpc.common.RpcConstants.VERSION,
                "RpcConstants.VERSION derives from the generated build version (no duplicate)");
    }

    @Test
    void initialize_noParams_defaultsToTheLegacyCeiling() {
        var h = handler(Map.of());
        var res = result(call(h,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));
        assertEquals(McpHandler.INITIALIZE_MAX_VERSION, res.get("protocolVersion"),
                "initialize defaults to the newest revision that still HAS initialize");
        assertNotEquals(McpHandler.PROTOCOL_VERSION, res.get("protocolVersion"),
                "initialize must never negotiate the revision that removed it");
    }

    // --- notifications ------------------------------------------------------------------

    @Test
    void notification_noId_emptyBodyAnd202Header() {
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        byte[] out = h.handle(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                resHeaders, new DefaultHttpHeaders());

        assertEquals(0, out.length, "notifications return an empty body");
        assertEquals(1, resHeaders.size(), "exactly the status-override header is added");
        var header = resHeaders.get(0);
        assertEquals("x-krpc-http-status", header.name.toString(), "status-override header name");
        assertEquals("202", header.value, "notifications map to HTTP 202");
    }

    // --- tools/list ---------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void toolsList_agentToolPresent_schemaAndConstraintsSurface() {
        var h = handler(Map.of("Calc/add", okInv("{}"), "Calc/ping", okInv("{}")));
        var tools = (List<Map<String, Object>>) result(
                call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).get("tools");

        var names = tools.stream().map(t -> (String) t.get("name")).toList();
        assertTrue(names.contains("Calc_add"), "agentTool method Calc_add present: " + names);
        assertTrue(names.contains("Calc_ping"), "agentTool method Calc_ping present: " + names);
        // Red line 2: only the agentTool subset we fed surfaces; nothing else is invented.
        assertFalse(names.contains("Calc_secret"), "non-agentTool method absent: " + names);

        var add = tools.stream().filter(t -> "Calc_add".equals(t.get("name"))).findFirst().orElseThrow();
        var inputSchema = (Map<String, Object>) add.get("inputSchema");
        assertEquals("object", inputSchema.get("type"), "inputSchema is an object (MCP requires it)");

        var props = (Map<String, Object>) inputSchema.get("properties");
        var nameProp = (Map<String, Object>) props.get("name");
        assertEquals("string", nameProp.get("type"), "name field type");
        // @NotBlank -> minLength:1 + required.
        assertEquals(1, ((Number) nameProp.get("minLength")).intValue(), "@NotBlank yields minLength:1");
        var required = (List<Object>) inputSchema.get("required");
        assertTrue(required.contains("name"), "@NotBlank yields required: " + required);
        // @Doc -> description.
        assertEquals("the person name", nameProp.get("description"), "@Doc yields description");
    }

    // --- tools/call: happy path ---------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void toolsCall_success_textAndStructuredContent() {
        WebInvoker web = (in, md) ->
                new ServerResult(OutputProto.newBuilder().setC(0).setUtf8("{\"m\":1}").build());
        var h = handler(Map.of("Calc/add", web));

        var env = call(h, toolsCall("Calc_add", "{\"name\":\"neo\"}"));
        assertFalse(isError(env), "code 0 -> isError:false");
        assertEquals("{\"m\":1}", firstText(env), "text content is the raw data JSON");

        var structured = result(env).get("structuredContent");
        assertInstanceOf(Map.class, structured, "structuredContent is the unwrapped object");
        assertEquals(1, ((Number) ((Map<String, Object>) structured).get("m")).intValue());
    }

    @Test
    void toolsCall_scalarData_noStructuredContent() {
        // A non-object data payload must NOT become structuredContent (spec: object only).
        WebInvoker web = (in, md) ->
                new ServerResult(OutputProto.newBuilder().setC(0).setUtf8("\"pong\"").build());
        var h = handler(Map.of("Calc/add", web));

        var env = call(h, toolsCall("Calc_add", "{\"name\":\"neo\"}"));
        assertFalse(isError(env));
        assertEquals("\"pong\"", firstText(env));
        assertNull(result(env).get("structuredContent"), "scalar data yields no structuredContent");
    }

    // --- tools/call: CREDENTIAL RED LINE (1) --------------------------------------------

    @Test
    void toolsCall_credentialCheckEnforced_notBypassed() {
        // The fake mirrors the gRPC entry: no Authorization in the Metadata -> UNAUTHENTICATED.
        // McpHandler.toMetadata must copy the inbound "authorization" header, and a thrown
        // StatusException must surface as a tool error (isError), never a silent success.
        WebInvoker credGuarded = (in, md) -> {
            if (null == md.get(McpHandler.AUTHORIZATION)) {
                throw new StatusException(Status.UNAUTHENTICATED);
            }
            return ok("{\"ok\":true}");
        };
        var h = handler(Map.of("Calc/add", credGuarded));
        var body = toolsCall("Calc_add", "{\"name\":\"neo\"}");

        // No Authorization header: credential failure -> tool error, not a fake success.
        var noAuth = call(h, body, new DefaultHttpHeaders(), new ArrayList<>());
        assertTrue(isError(noAuth), "missing credential must surface as isError:true, not success");

        // With Authorization header: propagated through Metadata -> success.
        var authed = new DefaultHttpHeaders();
        authed.add("authorization", "Bearer x");
        var withAuth = call(h, body, authed, new ArrayList<>());
        assertFalse(isError(withAuth), "valid credential propagates -> success");
    }

    // --- tools/call: unknown tool (RED LINE 2) ------------------------------------------

    @Test
    void toolsCall_unknownTool_invalidParams() {
        // A name absent from the registry (unknown / non-agentTool / hidden) -> -32602.
        var h = handler(Map.of("Calc/add", okInv("{}")));
        var env = call(h, toolsCall("Calc_secret", "{}"));
        assertEquals(-32602, errorCode(env), "unknown/non-agentTool tool -> INVALID_PARAMS");
    }

    // --- tools/call: business failure ---------------------------------------------------

    @Test
    void toolsCall_businessFailure_isToolError() {
        // Non-zero RpcResult code is a tool execution error, distinct from a protocol error.
        WebInvoker web = (in, md) ->
                new ServerResult(OutputProto.newBuilder().setC(5).setM("nope").build());
        var h = handler(Map.of("Calc/add", web));

        var env = call(h, toolsCall("Calc_add", "{\"name\":\"neo\"}"));
        assertTrue(isError(env), "code!=0 -> isError:true");
        // AGENT-002 finding #2: the error content is the {code,message} envelope JSON, not a
        // bare message string.
        var envelope = (Map<?, ?>) JsonUtils.parse(firstText(env), Object.class);
        assertEquals(5, ((Number) envelope.get("code")).intValue(), "RpcResult code surfaced");
        assertEquals("nope", envelope.get("message"), "business message surfaced in envelope");
    }

    // --- protocol errors ----------------------------------------------------------------

    @Test
    void malformedBody_parseError() {
        var h = handler(Map.of());
        assertEquals(-32700, errorCode(call(h, "{ this is not json")), "malformed JSON -> PARSE_ERROR");
    }

    @Test
    void unknownMethod_methodNotFound() {
        var h = handler(Map.of());
        var env = call(h, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"foo/bar\"}");
        assertEquals(-32601, errorCode(env), "unknown method -> METHOD_NOT_FOUND");
    }

    // --- arg-shape ----------------------------------------------------------------------

    @Test
    void toolsCall_argShape_noArgEmptyInput_dtoArgForwardedVerbatim() {
        var addInput = new AtomicReference<InputProto>();
        var pingInput = new AtomicReference<InputProto>();
        WebInvoker addInv = (in, md) -> { addInput.set(in); return ok("{}"); };
        WebInvoker pingInv = (in, md) -> { pingInput.set(in); return ok("{}"); };
        var h = handler(Map.of("Calc/add", addInv, "Calc/ping", pingInv));

        // No-arg tool: no arguments -> empty krpc input (utf8 unset).
        var pingEnv = call(h, toolsCall("Calc_ping", null));
        assertFalse(isError(pingEnv), "no-arg tool dispatches");
        assertFalse(pingInput.get().hasUtf8(), "no-arg tool sends no krpc payload");
        assertTrue(pingInput.get().getUtf8().isEmpty(), "no-arg utf8 empty");

        // DTO-arg tool: arguments forwarded verbatim as the krpc JSON input.
        var addEnv = call(h, toolsCall("Calc_add", "{\"name\":\"neo\"}"));
        assertFalse(isError(addEnv), "DTO-arg tool dispatches");
        assertEquals("{\"name\":\"neo\"}", addInput.get().getUtf8(),
                "DTO arguments forwarded verbatim as krpc input");
    }

    // --- protocol-version header (advisory #2 transport: MCP-Protocol-Version) -----------

    /** The value of the STATUS_OVERRIDE header in {@code resHeaders}, or null if absent. */
    private static String statusOverride(List<AsciiHeader> resHeaders) {
        for (var h : resHeaders) {
            if (h.name.contentEqualsIgnoreCase("x-krpc-http-status")) {
                return h.value;
            }
        }
        return null;
    }

    private static HttpHeaders withVersion(String version) {
        return new DefaultHttpHeaders().set("mcp-protocol-version", version);
    }

    @Test
    void postAfterInitialize_unsupportedVersionHeader_is400InvalidRequest() {
        // Spec 2025-06-18: a request (other than initialize) carrying an unsupported
        // MCP-Protocol-Version MUST be rejected — HTTP 400 + a JSON-RPC INVALID_REQUEST.
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var env = call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                withVersion("1999-01-01"), resHeaders);

        assertEquals("400", statusOverride(resHeaders),
                "unsupported MCP-Protocol-Version maps to HTTP 400: " + resHeaders);
        assertEquals(-32600, errorCode(env),
                "unsupported version -> JSON-RPC INVALID_REQUEST (-32600)");
    }

    @Test
    void postAfterInitialize_supportedVersionHeader_dispatchesNormally() {
        // A version in SUPPORTED_VERSIONS must pass the gate: no 400, a normal result.
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var env = call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                withVersion("2025-06-18"), resHeaders);

        assertNull(statusOverride(resHeaders),
                "supported version adds no status override: " + resHeaders);
        assertInstanceOf(Map.class, env.get("result"),
                "supported version dispatches to a normal result: " + env);
    }

    @Test
    void postAfterInitialize_noVersionHeader_dispatchesNormally() {
        // Absent header -> assume the default, do NOT fail (spec: only present-and-bad is 400).
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var env = call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                new DefaultHttpHeaders(), resHeaders);

        assertNull(statusOverride(resHeaders),
                "no version header adds no status override: " + resHeaders);
        assertInstanceOf(Map.class, env.get("result"),
                "no version header dispatches to a normal result: " + env);
    }

    // --- 2026-07-28: server/discover ----------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void serverDiscover_announcesVersionsCapabilitiesServerInfoInstructionsAndCacheHints() {
        // Spec 2026-07-28 MUST: the stateless replacement for initialize. One response must
        // carry everything a client needs before its first call.
        var h = handler(Map.of());
        var res = result(call(h, withMeta("server/discover", "2026-07-28")));

        var versions = (List<String>) res.get("supportedVersions");
        assertTrue(versions.contains("2026-07-28"), "07-28 advertised: " + versions);
        assertTrue(versions.contains("2025-11-25"), "11-25 advertised: " + versions);
        // Dual track: the old line stays advertised through the deprecation window.
        assertTrue(versions.contains("2025-06-18"), "old line still advertised: " + versions);
        assertEquals("2026-07-28", McpHandler.PROTOCOL_VERSION, "latest implemented version");

        var caps = (Map<String, Object>) res.get("capabilities");
        assertTrue(caps.containsKey("tools"), "tools capability present: " + caps);

        var info = (Map<String, Object>) res.get("serverInfo");
        assertEquals("test-app", info.get("name"), "serverInfo.name = app name");
        assertEquals(McpHandler.krpcVersion(), info.get("version"), "serverInfo.version = build version");

        var instructions = (String) res.get("instructions");
        assertNotNull(instructions, "instructions present (natural-language usage for the LLM)");
        assertTrue(instructions.contains("tools/list") && instructions.contains("isError"),
                "instructions tell the LLM how to list and how failures look: " + instructions);

        assertEquals(86400000L, ((Number) res.get("ttlMs")).longValue(), "discover ttlMs");
        assertEquals("public", res.get("cacheScope"), "discover cacheScope");
    }

    @Test
    void serverDiscover_withoutMetaVersion_is400InvalidRequest() {
        // server/discover exists ONLY in 2026-07-28, so it has no legacy callers: the REQUIRED
        // per-request version is enforced literally here — absent is 400, not a legacy pass.
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var env = call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\"}",
                new DefaultHttpHeaders(), resHeaders);

        assertEquals("400", statusOverride(resHeaders),
                "discover without a stated version is 400: " + resHeaders);
        assertEquals(-32600, errorCode(env), "missing _meta version on discover -> INVALID_REQUEST");
    }

    @Test
    void serverDiscover_unsupportedMetaVersion_is400InvalidRequest() {
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var env = call(h, withMeta("server/discover", "1999-01-01"),
                new DefaultHttpHeaders(), resHeaders);

        assertEquals("400", statusOverride(resHeaders), "discover version gate: " + resHeaders);
        assertEquals(-32600, errorCode(env), "unsupported version on discover -> INVALID_REQUEST");
    }

    // --- method x declared-version compatibility -----------------------------------------

    @Test
    void serverDiscover_declaringALegacyVersion_is400InvalidRequest() {
        // The version is supported by this server, but server/discover did not exist in it. A
        // client asserting a legacy wire while calling a 07-28-only method is confused, and
        // guessing which half it meant is exactly what a middleware must not do.
        var h = handler(Map.of());
        for (var legacy : List.of("2025-11-25", "2025-06-18", "2024-11-05")) {
            var resHeaders = new ArrayList<AsciiHeader>();
            var env = call(h, withMeta("server/discover", legacy), new DefaultHttpHeaders(), resHeaders);

            assertEquals("400", statusOverride(resHeaders), legacy + " discover -> HTTP 400");
            assertEquals(-32600, errorCode(env), legacy + " discover -> INVALID_REQUEST");
        }
    }

    @Test
    void initializeAndPing_declaring20260728_are400InvalidRequest() {
        // 2026-07-28 REMOVED initialize and ping: a client that declares that revision cannot
        // coherently call them. Legacy clients declare no _meta at all, so nobody breaks.
        var h = handler(Map.of());
        for (var removed : List.of("initialize", "ping")) {
            var resHeaders = new ArrayList<AsciiHeader>();
            var env = call(h, withMeta(removed, "2026-07-28"), new DefaultHttpHeaders(), resHeaders);

            assertEquals("400", statusOverride(resHeaders), removed + " under 07-28 -> HTTP 400");
            assertEquals(-32600, errorCode(env), removed + " under 07-28 -> INVALID_REQUEST");
        }
    }

    @Test
    void initializeAndPing_declaringALegacyVersion_stillWork() {
        // Non-vacuity for the test above: the same methods with a coherent declared version
        // dispatch normally, so the 400 is the compatibility rule and not a blanket block.
        var h = handler(Map.of());
        for (var legacy : List.of("2025-11-25", "2025-06-18")) {
            var initHeaders = new ArrayList<AsciiHeader>();
            var initEnv = call(h, withMeta("initialize", legacy), new DefaultHttpHeaders(), initHeaders);
            assertNull(statusOverride(initHeaders), "initialize under " + legacy + " is not gated");
            // The declared _meta version is a wire claim, not the negotiation input: the body's
            // params.protocolVersion decides, and this body carries none -> the legacy ceiling.
            assertEquals(McpHandler.INITIALIZE_MAX_VERSION, result(initEnv).get("protocolVersion"),
                    "initialize under " + legacy + " still negotiates normally");

            var pingHeaders = new ArrayList<AsciiHeader>();
            var pingEnv = call(h, withMeta("ping", legacy), new DefaultHttpHeaders(), pingHeaders);
            assertNull(statusOverride(pingHeaders), "ping under " + legacy + " is not gated");
            assertInstanceOf(Map.class, pingEnv.get("result"), "ping under " + legacy + " still answers");
        }
    }

    @Test
    void toolsMethods_declaring20260728_areUnaffectedByTheCompatibilityRule() {
        // Only the three methods whose existence changed are constrained; tools/* live in both.
        var h = handler(Map.of("Calc/add", okInv("{\"ok\":true}")));
        var resHeaders = new ArrayList<AsciiHeader>();
        var env = call(h, withMeta("tools/list", "2026-07-28"), new DefaultHttpHeaders(), resHeaders);

        assertNull(statusOverride(resHeaders), "tools/list is valid in 07-28: " + resHeaders);
        assertInstanceOf(Map.class, env.get("result"), "tools/list dispatches: " + env);
    }

    // --- 2026-07-28: per-request _meta protocol version ----------------------------------

    /** A request whose canonical {@code params._meta} states the protocol version. */
    private static String withMeta(String method, String version) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":"
                + "{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + version + "\"}}}";
    }

    /** A request whose {@code params._meta} carries the given raw JSON in place of a version. */
    private static String withRawMeta(String metaJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":"
                + "{\"_meta\":" + metaJson + "}}";
    }

    @Test
    void metaProtocolVersion_newLineWithoutInitialize_dispatchesNormally() {
        // 07-28 clients never call initialize: they state the version per request in params._meta.
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var env = call(h, withMeta("tools/list", "2026-07-28"), new DefaultHttpHeaders(), resHeaders);

        assertNull(statusOverride(resHeaders), "supported _meta version adds no 400: " + resHeaders);
        assertInstanceOf(Map.class, env.get("result"), "07-28 request dispatches: " + env);
    }

    @Test
    void metaProtocolVersion_unsupported_is400InvalidRequest() {
        // Mirrors the header gate exactly: present-and-unsupported MUST be 400 + -32600.
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var env = call(h, withMeta("tools/list", "1999-01-01"), new DefaultHttpHeaders(), resHeaders);

        assertEquals("400", statusOverride(resHeaders),
                "unsupported _meta protocolVersion maps to HTTP 400: " + resHeaders);
        assertEquals(-32600, errorCode(env), "unsupported _meta version -> INVALID_REQUEST");
    }

    @Test
    void metaProtocolVersion_malformedShapes_are400InvalidRequest() {
        // A version the server cannot even compare is a 400, never a silent shrug: the client
        // asserted something about the wire and must be told the assertion was rejected.
        record Case(String label, String metaJson) {}
        var cases = List.of(
                new Case("_meta is a number", "7"),
                new Case("_meta is an array", "[]"),
                new Case("_meta is a string", "\"2026-07-28\""),
                new Case("_meta is null", "null"),
                new Case("version is a number",
                        "{\"io.modelcontextprotocol/protocolVersion\":20260728}"),
                new Case("version is an array",
                        "{\"io.modelcontextprotocol/protocolVersion\":[\"2026-07-28\"]}"),
                new Case("version is an object",
                        "{\"io.modelcontextprotocol/protocolVersion\":{\"v\":\"2026-07-28\"}}"),
                new Case("version is null",
                        "{\"io.modelcontextprotocol/protocolVersion\":null}"),
                new Case("version is blank",
                        "{\"io.modelcontextprotocol/protocolVersion\":\"  \"}"));

        var h = handler(Map.of());
        for (var c : cases) {
            var resHeaders = new ArrayList<AsciiHeader>();
            var env = call(h, withRawMeta(c.metaJson()), new DefaultHttpHeaders(), resHeaders);
            assertEquals("400", statusOverride(resHeaders), c.label() + " -> HTTP 400");
            assertEquals(-32600, errorCode(env), c.label() + " -> INVALID_REQUEST");
        }
    }

    @Test
    void metaProtocolVersion_metaWithoutTheVersionKey_isTreatedAsAbsent() {
        // A _meta carrying other keys makes no version claim -> legacy path, not a 400.
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var env = call(h, withRawMeta("{\"vendor.example/trace\":\"x\"}"),
                new DefaultHttpHeaders(), resHeaders);

        assertNull(statusOverride(resHeaders), "no version claim, no 400: " + resHeaders);
        assertInstanceOf(Map.class, env.get("result"), "request dispatches: " + env);
    }

    @Test
    void metaProtocolVersion_messageLevelMeta_isNotACanonicalPosition() {
        // params._meta is the ONLY accepted position: two accepted positions are two things to
        // spoof. A top-level-only _meta counts as absent — including a bogus version in it,
        // which must NOT be enforced (it is not a claim we read) nor make the request fail.
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\","
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"1999-01-01\"}}";
        var env = call(h, body, new DefaultHttpHeaders(), resHeaders);

        assertNull(statusOverride(resHeaders),
                "top-level _meta is not read, so it cannot trip the gate: " + resHeaders);
        assertInstanceOf(Map.class, env.get("result"), "legacy path dispatches: " + env);
    }

    @Test
    void initialize_malformedMetaVersion_is400_gateAppliesToEveryMethod() {
        // "Everywhere" includes initialize: the _meta gate is not scoped to the new line.
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-06-18\","
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":42}}}";
        var env = call(h, body, new DefaultHttpHeaders(), resHeaders);

        assertEquals("400", statusOverride(resHeaders), "initialize is not exempt: " + resHeaders);
        assertEquals(-32600, errorCode(env), "malformed _meta on initialize -> INVALID_REQUEST");
    }

    @Test
    void noVersionStatedAtAll_legacyClientStillWorks() {
        // Deliberate dual-stack deviation from the 07-28 REQUIRED wording: neither params._meta
        // nor MCP-Protocol-Version = pre-07-28 client, which must keep working on this endpoint.
        var h = handler(Map.of("Calc/add", okInv("{\"ok\":true}")));
        var resHeaders = new ArrayList<AsciiHeader>();
        var env = call(h, toolsCall("Calc_add", "{\"name\":\"neo\"}"),
                new DefaultHttpHeaders(), resHeaders);

        assertNull(statusOverride(resHeaders), "legacy client is not rejected: " + resHeaders);
        assertFalse(isError(env), "legacy tools/call still dispatches: " + env);
    }

    // --- JSON-RPC id discipline ---------------------------------------------------------

    @Test
    void explicitNullId_isInvalidRequest_notANotification() {
        // "id":null is a request with an invalid RequestId, not the absence of id. Treating it
        // as a notification would answer 202 to a client that is waiting for a result.
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var env = call(h, "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/list\"}",
                new DefaultHttpHeaders(), resHeaders);

        assertNotNull(env, "explicit null id must produce a body, not an empty 202");
        assertEquals(-32600, errorCode(env), "explicit null id -> INVALID_REQUEST");
        assertNull(statusOverride(resHeaders), "no 202 status override was added: " + resHeaders);
    }

    // --- 2026-07-28: L7 header / body consistency ---------------------------------------

    @Test
    void mcpMethodHeader_mismatchingBody_is400HeaderMismatch() {
        // The LB routes on the header while the server executes the body: a disagreement is a
        // split-brain attack surface, so it MUST be rejected rather than silently resolved.
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var headers = new DefaultHttpHeaders().set("Mcp-Method", "tools/call");
        var env = call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", headers, resHeaders);

        assertEquals("400", statusOverride(resHeaders), "header mismatch maps to HTTP 400: " + resHeaders);
        assertEquals(-32020, errorCode(env), "header mismatch -> -32020");
        assertEquals("HeaderMismatch", error(env).get("message"), "spec error message");
    }

    @Test
    void mcpMethodHeader_matchingBody_dispatchesNormally() {
        // Non-vacuity for the test above: the same header name, agreeing, must pass the gate.
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var headers = new DefaultHttpHeaders().set("Mcp-Method", "tools/list");
        var env = call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", headers, resHeaders);

        assertNull(statusOverride(resHeaders), "matching header adds no status override: " + resHeaders);
        assertInstanceOf(Map.class, env.get("result"), "matching header dispatches: " + env);
    }

    @Test
    void mcpNameHeader_mismatchingToolName_is400HeaderMismatch() {
        var h = handler(Map.of("Calc/add", okInv("{}")));
        var resHeaders = new ArrayList<AsciiHeader>();
        var headers = new DefaultHttpHeaders()
                .set("Mcp-Method", "tools/call")
                .set("Mcp-Name", "Calc_ping");
        var env = call(h, toolsCall("Calc_add", "{\"name\":\"neo\"}"), headers, resHeaders);

        assertEquals("400", statusOverride(resHeaders), "name mismatch maps to HTTP 400: " + resHeaders);
        assertEquals(-32020, errorCode(env), "Mcp-Name vs params.name mismatch -> -32020");
    }

    @Test
    void mcpNameHeader_matchingToolName_dispatchesNormally() {
        var h = handler(Map.of("Calc/add", okInv("{\"ok\":true}")));
        var resHeaders = new ArrayList<AsciiHeader>();
        var headers = new DefaultHttpHeaders()
                .set("Mcp-Method", "tools/call")
                .set("Mcp-Name", "Calc_add");
        var env = call(h, toolsCall("Calc_add", "{\"name\":\"neo\"}"), headers, resHeaders);

        assertNull(statusOverride(resHeaders), "matching headers add no status override: " + resHeaders);
        assertFalse(isError(env), "matching headers dispatch to a normal tool result: " + env);
    }

    @Test
    void mcpNameHeader_bodyNameMissingOrNotAString_is400HeaderMismatch() {
        // A name header over a body with no usable name describes nothing — the header and the
        // body cannot be shown to agree, so it is a mismatch, not a pass.
        record Case(String label, String paramsJson) {}
        var cases = List.of(
                new Case("params.name missing", "{\"arguments\":{}}"),
                new Case("params.name is a number", "{\"name\":7}"),
                new Case("params.name is null", "{\"name\":null}"),
                new Case("params absent", null));

        var h = handler(Map.of("Calc/add", okInv("{}")));
        for (var c : cases) {
            var resHeaders = new ArrayList<AsciiHeader>();
            var body = null == c.paramsJson()
                    ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                            + c.paramsJson() + "}";
            var headers = new DefaultHttpHeaders().set("Mcp-Name", "Calc_add");
            var env = call(h, body, headers, resHeaders);

            assertEquals("400", statusOverride(resHeaders), c.label() + " -> HTTP 400");
            assertEquals(-32020, errorCode(env), c.label() + " -> HeaderMismatch");
        }
    }

    @Test
    void duplicateRoutingHeaders_withDistinctValues_are400HeaderMismatch() {
        // Two values for one routing header is the same split-brain in a different shape: each
        // hop may read a different one. Only DISTINCT values conflict — a repeated identical
        // value is harmless.
        var h = handler(Map.of("Calc/add", okInv("{\"ok\":true}")));

        var dupMethod = new ArrayList<AsciiHeader>();
        var methodHeaders = new DefaultHttpHeaders()
                .add("Mcp-Method", "tools/list")
                .add("Mcp-Method", "tools/call");
        var env1 = call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                methodHeaders, dupMethod);
        assertEquals("400", statusOverride(dupMethod), "duplicate Mcp-Method -> HTTP 400");
        assertEquals(-32020, errorCode(env1), "duplicate Mcp-Method -> HeaderMismatch");

        var dupName = new ArrayList<AsciiHeader>();
        var nameHeaders = new DefaultHttpHeaders()
                .add("Mcp-Method", "tools/call")
                .add("Mcp-Name", "Calc_add")
                .add("Mcp-Name", "Calc_ping");
        var env2 = call(h, toolsCall("Calc_add", "{\"name\":\"neo\"}"), nameHeaders, dupName);
        assertEquals("400", statusOverride(dupName), "duplicate Mcp-Name -> HTTP 400");
        assertEquals(-32020, errorCode(env2), "duplicate Mcp-Name -> HeaderMismatch");

        // Mcp-Name names a tool, so it is only meaningful on tools/call: the method is
        // short-circuited BEFORE the header is read, and any shape of it elsewhere — duplicates
        // included — is ignored rather than half-validated.
        var nameOnList = new ArrayList<AsciiHeader>();
        var listHeaders = new DefaultHttpHeaders()
                .add("Mcp-Method", "tools/list")
                .add("Mcp-Name", "Calc_add")
                .add("Mcp-Name", "Calc_ping");
        var envList = call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                listHeaders, nameOnList);
        assertNull(statusOverride(nameOnList),
                "Mcp-Name is not checked outside tools/call: " + nameOnList);
        assertInstanceOf(Map.class, envList.get("result"), "tools/list dispatches: " + envList);

        // Non-vacuity: the same header repeated with the SAME value is not a conflict.
        var repeated = new ArrayList<AsciiHeader>();
        var sameHeaders = new DefaultHttpHeaders()
                .add("Mcp-Method", "tools/list")
                .add("Mcp-Method", "tools/list");
        var env3 = call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                sameHeaders, repeated);
        assertNull(statusOverride(repeated), "identical repeats are not a conflict: " + repeated);
        assertInstanceOf(Map.class, env3.get("result"), "identical repeats dispatch: " + env3);
    }

    @Test
    void noL7Headers_oldClientsUnaffected() {
        // Absent Mcp-Method/Mcp-Name is fine: pre-07-28 clients never send them.
        var h = handler(Map.of("Calc/add", okInv("{}")));
        var resHeaders = new ArrayList<AsciiHeader>();
        var env = call(h, toolsCall("Calc_add", "{\"name\":\"neo\"}"),
                new DefaultHttpHeaders(), resHeaders);

        assertNull(statusOverride(resHeaders), "no L7 headers, no 400: " + resHeaders);
        assertFalse(isError(env), "old-style call still dispatches: " + env);
    }

    // --- 2026-07-28: tools/list cache hints + deterministic order ------------------------

    @Test
    void toolsList_carriesCacheHints() {
        var h = handler(Map.of("Calc/add", okInv("{}"), "Calc/ping", okInv("{}")));
        var res = result(call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));

        assertEquals(86400000L, ((Number) res.get("ttlMs")).longValue(),
                "tool set is static per boot -> long TTL");
        assertEquals("public", res.get("cacheScope"),
                "tool set is identical for every caller -> public");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsList_isSortedByToolName_regardlessOfDiscoveryOrder() {
        // Reflection order is not stable across JVMs/builds; the listing must be.
        var zeta = new Api();
        zeta.setName("Zeta");
        zeta.setMethods(List.of(method("zoo"), method("abc")));
        var alpha = new Api();
        alpha.setName("Alpha");
        alpha.setMethods(List.of(method("beta")));

        var registry = new McpToolRegistry();
        registry.init(Map.of(), new ApiMeta("test-app", List.of(zeta, alpha), List.of()));
        var h = new McpHandler();
        h.registry = registry;

        var tools = (List<Map<String, Object>>) result(
                call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).get("tools");
        var names = tools.stream().map(t -> (String) t.get("name")).toList();

        assertEquals(List.of("Alpha_beta", "Zeta_abc", "Zeta_zoo"), names,
                "tools sorted by name, not by discovery order: " + names);
    }

    private static Method method(String name) {
        var m = new Method();
        m.setName(name);
        return m;
    }

    @Test
    void initialize_unsupportedVersionHeader_isExemptFromThe400Gate() {
        // initialize negotiates the version via the body, so it is exempt from the header
        // gate: even an unsupported MCP-Protocol-Version header must NOT force a 400.
        var h = handler(Map.of());
        var resHeaders = new ArrayList<AsciiHeader>();
        var env = call(h,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                withVersion("1999-01-01"), resHeaders);

        assertNull(statusOverride(resHeaders),
                "initialize is exempt: no 400 from a bad version header: " + resHeaders);
        assertEquals(McpHandler.INITIALIZE_MAX_VERSION, result(env).get("protocolVersion"),
                "initialize still negotiates a normal result");
    }
}
