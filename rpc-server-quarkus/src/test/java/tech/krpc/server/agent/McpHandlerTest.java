package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        // requested -> echoed; unknown -> falls back to the latest.
        record Case(String requested, String expected) {}
        var cases = List.of(
                new Case("2025-06-18", "2025-06-18"),
                new Case("2025-03-26", "2025-03-26"),
                new Case("1999-01-01", McpHandler.PROTOCOL_VERSION));

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
            assertEquals("krpc", ((Map<?, ?>) info).get("name"), "serverInfo.name");
        }
    }

    @Test
    void initialize_noParams_defaultsToLatestVersion() {
        var h = handler(Map.of());
        var res = result(call(h,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));
        assertEquals(McpHandler.PROTOCOL_VERSION, res.get("protocolVersion"));
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
        assertEquals("nope", firstText(env), "business message surfaced as tool error text");
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
        assertEquals(McpHandler.PROTOCOL_VERSION, result(env).get("protocolVersion"),
                "initialize still negotiates a normal result");
    }
}
