package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import tech.krpc.http.server.AsciiHeader;
import tech.krpc.server.RpcServerBuilder;
import tech.krpc.util.JsonUtils;

/**
 * ADR-0004 (AGENT-001 P1), advisory #3: the <em>agentTool gate</em> — the third exposure
 * filter — must block a REAL {@code @UnsafeWeb(agentTool=false)} method, not merely an
 * unknown tool name.
 *
 * <p>Fixtures: {@link AgentTestServices.AgentToolEcho} ({@code @UnsafeWeb(agentTool=true)})
 * and {@link AgentTestServices.WebEcho} ({@code @UnsafeWeb}, agentTool defaults false) are
 * both genuinely web-exposed, so any test asserting WebEcho is absent from MCP is asserting
 * on a method the server DID register on the web surface — the gate, not a missing name.
 *
 * <p>Two levels, one contract:
 * <ul>
 *   <li><b>Builder</b>: the web surface and the MCP surface diverge at build time —
 *       {@code webMethods()}/{@code webApiMeta()} carry BOTH web services while
 *       {@code mcpMethods()}/{@code mcpApiMeta()} carry ONLY the agentTool service.</li>
 *   <li><b>Handler</b>: driven off that same {@code mcpMethods()}/{@code mcpApiMeta()},
 *       {@code tools/list} omits the WebEcho method and {@code tools/call} for it is
 *       {@code -32602}, while the agentTool method is listed AND callable (so the
 *       {@code -32602} is a real block, not a blanket failure).</li>
 * </ul>
 */
class McpAgentToolGateTest {

    // Tool names via the production single-source-of-truth (McpToolRegistry.toolName).
    private static final String AGENT_TOOL = McpToolRegistry.toolName("AgentToolEcho", "tool");
    private static final String WEB_TOOL = McpToolRegistry.toolName("WebEcho", "echo");
    // App-relative dispatch keys ("Service/method"), as RpcServerBuilder.webKey emits.
    private static final String AGENT_KEY = "AgentToolEcho/tool";
    private static final String WEB_KEY = "WebEcho/echo";

    /** High port; build() wires the surfaces but does NOT bind (startServer would). */
    private static RpcServerBuilder build() throws Exception {
        return new RpcServerBuilder.Builder("testapp", 59124)
                .addService(new AgentTestServices.AgentToolEcho())
                .addService(new AgentTestServices.WebEcho())
                .addService(new AgentTestServices.HiddenAdmin())
                .build();
    }

    // --- builder-level: the two surfaces diverge at build time --------------------------

    @Test
    void mcpMethods_containOnlyAgentToolService() throws Exception {
        var mcp = build().mcpMethods().keySet();

        assertTrue(mcp.contains(AGENT_KEY), "agentTool method must be an MCP tool: " + mcp);
        // A REAL @UnsafeWeb method that is not agentTool must NOT enter the MCP dispatch map.
        assertFalse(mcp.contains(WEB_KEY),
                "@UnsafeWeb(agentTool=false) method must be gated out of MCP dispatch: " + mcp);
        // Hidden (non-@UnsafeWeb) and internal meta services are filtered upstream anyway.
        assertTrue(mcp.stream().noneMatch(k -> k.contains("HiddenAdmin")),
                "hidden service must never reach MCP dispatch: " + mcp);
        assertTrue(mcp.stream().noneMatch(k -> k.contains("RpcMeta") || k.contains("MService")),
                "internal meta services must never reach MCP dispatch: " + mcp);
    }

    @Test
    void webMethods_containBothWebServices_provingSurfacesDiverge() throws Exception {
        var web = build().webMethods().keySet();

        // The point of the gate: WebEcho IS a real registered web method — the MCP surface
        // excludes it by policy (agentTool), not because it was never exposed.
        assertTrue(web.contains(WEB_KEY), "web service method must be on the web surface: " + web);
        assertTrue(web.contains(AGENT_KEY), "agentTool method is web-exposed too: " + web);
    }

    @Test
    void mcpApiMeta_discoversOnlyAgentToolService() throws Exception {
        var server = build();
        var mcpNames = server.mcpApiMeta().getApis().stream().map(a -> a.getName()).toList();
        var webNames = server.webApiMeta().getApis().stream().map(a -> a.getName()).toList();

        assertEquals(List.of("AgentToolEcho"), mcpNames,
                "tools/list discovery source must be the agentTool service ONLY: " + mcpNames);
        // Same fixture set, wider web discovery: proves divergence rather than a build glitch.
        assertTrue(webNames.contains("AgentToolEcho") && webNames.contains("WebEcho"),
                "web discovery carries both web services: " + webNames);
        assertFalse(webNames.contains("HiddenAdmin"), "hidden service is undiscoverable: " + webNames);
    }

    // --- handler-level: a REAL @UnsafeWeb method is blocked, agentTool one is reachable --

    @Test
    void toolsList_omitsAgentToolFalseMethod() throws Exception {
        var names = toolNames(handler());

        assertTrue(names.contains(AGENT_TOOL), "agentTool method must be listed: " + names);
        assertFalse(names.contains(WEB_TOOL),
                "@UnsafeWeb(agentTool=false) method must NOT be listed as an MCP tool: " + names);
    }

    @Test
    void toolsCall_realUnsafeWebMethodBlocked_agentToolMethodReachable() throws Exception {
        var h = handler();

        // The gate blocks a REAL @UnsafeWeb method by tool name -> -32602 (not a typo path:
        // WEB_TOOL names a method the server genuinely registered on the web surface).
        var blocked = call(h, toolsCall(WEB_TOOL, "{}"));
        assertEquals(-32602, errorCode(blocked),
                "gated real @UnsafeWeb(agentTool=false) method -> INVALID_PARAMS");

        // Non-vacuity: the SAME handler resolves the agentTool method (lookup succeeds ->
        // a JSON-RPC result envelope, never -32602). So -32602 above is the gate, not a
        // blanket failure. (Only registry.lookup()==null yields a top-level -32602.)
        var allowed = call(h, toolsCall(AGENT_TOOL, "{\"value\":\"ping\"}"));
        assertNull(allowed.get("error"),
                "agentTool method must not be gated: no top-level JSON-RPC error, got " + allowed);
        assertNotNull(allowed.get("result"), "agentTool method dispatches to a result: " + allowed);
    }

    // --- helpers ------------------------------------------------------------------------

    /** McpHandler wired off the live builder surfaces — the real gate, not a hand-fed map. */
    private static McpHandler handler() throws Exception {
        var server = build();
        var registry = new McpToolRegistry();
        registry.init(server.mcpMethods(), server.mcpApiMeta());
        var handler = new McpHandler();
        handler.registry = registry;
        return handler;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(McpHandler h, String body) {
        byte[] out = h.handle(body, new ArrayList<AsciiHeader>(), new DefaultHttpHeaders());
        var parsed = JsonUtils.parse(new String(out, StandardCharsets.UTF_8), Object.class);
        return (Map<String, Object>) parsed;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toolNames(McpHandler h) {
        var env = call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        var result = (Map<String, Object>) env.get("result");
        var tools = (List<Map<String, Object>>) result.get("tools");
        return tools.stream().map(t -> (String) t.get("name")).toList();
    }

    @SuppressWarnings("unchecked")
    private static int errorCode(Map<String, Object> env) {
        var err = (Map<String, Object>) env.get("error");
        assertNotNull(err, "expected a JSON-RPC error envelope, got: " + env);
        return ((Number) err.get("code")).intValue();
    }

    private static String toolsCall(String toolName, String argumentsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + toolName + "\",\"arguments\":" + argumentsJson + "}}";
    }
}
