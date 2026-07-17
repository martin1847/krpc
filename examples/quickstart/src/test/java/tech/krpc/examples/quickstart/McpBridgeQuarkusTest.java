package tech.krpc.examples.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import tech.krpc.util.JsonUtils;

/**
 * Wave 3 Phase B — container-level acceptance guard for the MCP Streamable HTTP bridge
 * ({@code POST /mcp}, JSON-RPC 2.0, MCP spec 2025-06-18; ADR-0004 / AGENT-001 P1).
 *
 * <p>The MCP bridge is <b>default OFF</b> ({@code rpc.server.mcp.enabled}); the flag gates
 * registration in {@code HttpHandlerExpose} so the path is byte-level absent when off. This class
 * turns it ON <em>for this class only</em> via a {@link TestProfile} whose
 * {@code getConfigOverrides()} sets {@code rpc.server.mcp.enabled=true} — Quarkus reboots the app
 * per profile, so this does NOT weaken the default-profile {@code AgentEndpointsQuarkusTest} nor the
 * OFF assertion in {@link McpDisabledQuarkusTest} (both run under the default = MCP off profile).
 *
 * <p>The bridge rides the standalone {@code tech.krpc.http.server.HttpServer} netty transport on
 * {@code http.port} (default 8080) — the SAME host as {@code /agent/*}, a <b>different</b> server
 * from the Quarkus HTTP port — so requests use a raw {@link HttpClient} against
 * {@code http://localhost:8080/mcp}, not RestAssured. Values below (protocolVersion echo,
 * serverInfo.name, Hello_hello schema, structuredContent) were confirmed by hand against the
 * fast-jar and native builds; these tests pin them so a bridge regression goes RED.
 */
@QuarkusTest
@TestProfile(McpBridgeQuarkusTest.McpEnabledProfile.class)
class McpBridgeQuarkusTest {

    // The MCP-enabled profile forces a Quarkus reboot (per-profile), which must re-bind the two
    // servers krpc starts by hand: the netty http-server (http.port) and the gRPC server
    // (rpc.server.port). Netty's shutdown is async (shutdownGracefully) and neither socket sets
    // SO_REUSEADDR, so if this class reused the defaults (8080/50051) the reboot could collide with
    // the still-closing default-profile boot -> BindException. This profile therefore claims its own
    // ports; the test targets the MCP netty port here, not the shared 8080.
    private static final int MCP_HTTP_PORT = 8091;

    /** Standalone netty agent server for the MCP-enabled profile (isolated http.port); NOT the Quarkus port. */
    private static final String MCP_URL = "http://localhost:" + MCP_HTTP_PORT + "/mcp";

    /** HTTP/1.1 explicitly: the netty pipeline is HttpRequestDecoder/HttpResponseEncoder (no h2). */
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** Flips the default-OFF MCP bridge ON for this class only, on isolated ports; Quarkus reboots per profile. */
    public static class McpEnabledProfile implements io.quarkus.test.junit.QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "rpc.server.mcp.enabled", "true",
                    "http.port", String.valueOf(MCP_HTTP_PORT),
                    "rpc.server.port", "50061");
        }
    }

    @Test
    void initialize_negotiatesVersionAndAdvertisesTools() throws Exception {
        HttpResponse<String> res = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"acc-test\",\"version\":\"0\"}}}");

        // On (flag=true) the path is registered -> 200; OFF it would be a 404 (see McpDisabledQuarkusTest).
        assertEquals(200, res.statusCode(), () -> "initialize body: " + res.body());
        Map<String, Object> result = resultOf(res.body());
        // The client's requested (supported) version is echoed, not silently replaced.
        assertEquals("2025-06-18", result.get("protocolVersion"),
                () -> "initialize did not echo protocolVersion: " + res.body());
        Map<String, Object> serverInfo = asMap(result.get("serverInfo"), "serverInfo");
        // AGENT-002 finding #5: serverInfo.name is the exposed app name ("quickstart"), not a
        // hardcoded "krpc", so a multi-service agent can verify which server it reached.
        assertEquals("quickstart", serverInfo.get("name"),
                () -> "serverInfo.name != app name (quickstart): " + res.body());
        // version is present and non-blank (krpc build version via jar manifest, fallback
        // RpcConstants.VERSION); never absent.
        Object version = serverInfo.get("version");
        assertInstanceOf(String.class, version, () -> "serverInfo.version missing: " + res.body());
        assertFalse(((String) version).isBlank(), () -> "serverInfo.version blank: " + res.body());
        // capabilities.tools present (object) -> the server declares it serves tools.
        Map<String, Object> caps = asMap(result.get("capabilities"), "capabilities");
        assertInstanceOf(Map.class, caps.get("tools"),
                () -> "capabilities.tools absent/not-object: " + res.body());
    }

    @Test
    void initializedNotification_returns202AcceptedEmptyBody() throws Exception {
        // A JSON-RPC notification (no id) -> Streamable HTTP 202 Accepted, empty body.
        HttpResponse<String> res = post(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, res.statusCode(), () -> "notifications/initialized not 202: " + res.body());
        assertTrue(res.body() == null || res.body().isEmpty(),
                () -> "notification body should be empty: " + res.body());
    }

    @Test
    void toolsList_exposesHelloWithInputAndOutputSchema() throws Exception {
        HttpResponse<String> res = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertEquals(200, res.statusCode(), () -> "tools/list body: " + res.body());
        Map<String, Object> result = resultOf(res.body());

        Map<String, Object> hello = findTool(result, "Hello_hello");
        assertNotNull(hello, () -> "tools/list missing Hello_hello: " + res.body());

        // inputSchema.required carries "name" (derived from HelloRequest @NotBlank).
        Map<String, Object> inputSchema = asMap(hello.get("inputSchema"), "inputSchema");
        Object required = inputSchema.get("required");
        assertInstanceOf(List.class, required, () -> "inputSchema.required not a list: " + res.body());
        assertTrue(((List<?>) required).contains("name"),
                () -> "inputSchema.required missing 'name': " + res.body());

        // outputSchema.properties has both message and timestamp (RpcResult<HelloReply> unwrapped).
        Map<String, Object> outputSchema = asMap(hello.get("outputSchema"), "outputSchema");
        Map<String, Object> outProps = asMap(outputSchema.get("properties"), "outputSchema.properties");
        assertTrue(outProps.containsKey("message"),
                () -> "outputSchema.properties missing 'message': " + res.body());
        assertTrue(outProps.containsKey("timestamp"),
                () -> "outputSchema.properties missing 'timestamp': " + res.body());
    }

    @Test
    void toolsCall_helloDispatchesThroughAgentPathAndReturnsStructuredContent() throws Exception {
        HttpResponse<String> res = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"Hello_hello\",\"arguments\":{\"name\":\"acc\"}}}");
        assertEquals(200, res.statusCode(), () -> "tools/call body: " + res.body());
        Map<String, Object> result = resultOf(res.body());

        // Success dispatch (RpcResult code 0) -> isError false, NOT a swallowed error.
        assertEquals(Boolean.FALSE, result.get("isError"),
                () -> "tools/call isError should be false: " + res.body());
        // structuredContent is the unwrapped HelloReply.data; message proves the real service ran.
        Map<String, Object> structured = asMap(result.get("structuredContent"), "structuredContent");
        Object message = structured.get("message");
        assertInstanceOf(String.class, message,
                () -> "structuredContent.message not a string: " + res.body());
        assertTrue(((String) message).contains("Hello, acc!"),
                () -> "structuredContent.message missing greeting: " + res.body());
    }

    @Test
    void toolsCall_unknownToolIsInvalidParams() throws Exception {
        // A tool that is not an agentTool method (or plain unknown) is opaque -> JSON-RPC -32602.
        HttpResponse<String> res = post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"Ghost_missing\",\"arguments\":{}}}");
        assertEquals(200, res.statusCode(), () -> "unknown-tool body: " + res.body());
        Map<String, Object> err = errorOf(res.body());
        assertEquals(-32602, ((Number) err.get("code")).intValue(),
                () -> "unknown tool not -32602: " + res.body());
    }

    // --- helpers ------------------------------------------------------------------------

    private HttpResponse<String> post(String jsonRpcBody) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(MCP_URL))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcBody))
                .build());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultOf(String body) {
        Map<String, Object> env = JsonUtils.parse(body, Map.class);
        assertEquals("2.0", env.get("jsonrpc"), () -> "not JSON-RPC 2.0: " + body);
        Object result = env.get("result");
        assertInstanceOf(Map.class, result, () -> "missing/invalid result envelope: " + body);
        return (Map<String, Object>) result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(String body) {
        Map<String, Object> env = JsonUtils.parse(body, Map.class);
        assertEquals("2.0", env.get("jsonrpc"), () -> "not JSON-RPC 2.0: " + body);
        Object error = env.get("error");
        assertInstanceOf(Map.class, error, () -> "expected error envelope: " + body);
        return (Map<String, Object>) error;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findTool(Map<String, Object> result, String name) {
        Object tools = result.get("tools");
        assertInstanceOf(List.class, tools, () -> "tools/list result.tools not a list: " + result);
        for (Object t : (List<Object>) tools) {
            if (t instanceof Map<?, ?> tool && name.equals(tool.get("name"))) {
                return (Map<String, Object>) tool;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o, String label) {
        assertInstanceOf(Map.class, o, () -> label + " not an object: " + o);
        return (Map<String, Object>) o;
    }

    /**
     * The netty agent server binds synchronously in {@code HttpServer.start()} during {@code @Startup},
     * so it is normally up before any test runs. Guard against a rare connect race (and the extra
     * jitter of the per-profile Quarkus reboot rebinding 8080) with a bounded retry (≤5s total) —
     * never an unconditional skip: the endpoint MUST ultimately answer.
     */
    private HttpResponse<String> send(HttpRequest request) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException connectFailure) {
                // Connection refused / reset before the netty server finished binding: retry briefly.
                last = connectFailure;
                Thread.sleep(100);
            }
        }
        fail("mcp endpoint at " + MCP_URL + " never answered within 5s "
                + "(is the netty HttpServer started? rpc.server.mcp.enabled override applied?): " + last);
        throw new AssertionError("unreachable");
    }
}
