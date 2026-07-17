package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tech.krpc.server.RpcServerBuilder;

/**
 * AGENT-002 item #5: method-level {@code @UnsafeWeb.AgentTool}. Interface-level
 * {@code agentTool=true} still exposes every method; on a plain {@code @UnsafeWeb} interface
 * only the {@code @AgentTool}-annotated methods become MCP tools; a method with no annotation
 * on either level is never an MCP tool (NS-6 all-OFF default).
 *
 * <p>Driven off the live {@link RpcServerBuilder} surfaces (real reflective scan), never a
 * hand-fed map, so it proves the scan itself — not a downstream re-filter.
 */
class McpMethodAgentToolScanTest {

    private static final String PARTIAL_EXPOSED = "PartialTool/exposed";
    private static final String PARTIAL_HIDDEN = "PartialTool/hidden";
    private static final String AGENT_KEY = "AgentToolEcho/tool";
    private static final String WEB_KEY = "WebEcho/echo";

    private static RpcServerBuilder build() throws Exception {
        return new RpcServerBuilder.Builder("testapp", 59125)
                .addService(new AgentTestServices.PartialTool())
                .addService(new AgentTestServices.AgentToolEcho())
                .addService(new AgentTestServices.WebEcho())
                .build();
    }

    @Test
    void methodLevelAgentTool_exposesOnlyAnnotatedMethod() throws Exception {
        var mcp = build().mcpMethods().keySet();

        // The @AgentTool method is an MCP tool; its sibling without the annotation is not.
        assertTrue(mcp.contains(PARTIAL_EXPOSED),
                "@UnsafeWeb.AgentTool method must be an MCP tool: " + mcp);
        assertFalse(mcp.contains(PARTIAL_HIDDEN),
                "un-annotated method on a plain @UnsafeWeb interface must NOT be an MCP tool: " + mcp);
    }

    @Test
    void interfaceLevelAgentTool_stillExposesEveryMethod() throws Exception {
        var mcp = build().mcpMethods().keySet();

        // Regression: interface-level agentTool=true is unchanged.
        assertTrue(mcp.contains(AGENT_KEY),
                "interface-level agentTool=true still exposes its method: " + mcp);
    }

    @Test
    void noAnnotation_noMcpExposure() throws Exception {
        var mcp = build().mcpMethods().keySet();

        // NS-6: @UnsafeWeb alone (agentTool defaults false, no method @AgentTool) = no MCP tool.
        assertFalse(mcp.contains(WEB_KEY),
                "@UnsafeWeb with no agentTool opt-in must have zero MCP exposure: " + mcp);
    }

    @Test
    void webSurfaceUnaffected_bothPartialMethodsWebExposed() throws Exception {
        var web = build().webMethods().keySet();

        // Method-level agentTool is a subset of web exposure: BOTH partial methods are still
        // web-reachable; only MCP exposure differs.
        assertTrue(web.contains(PARTIAL_EXPOSED) && web.contains(PARTIAL_HIDDEN),
                "both @UnsafeWeb methods stay on the web surface regardless of @AgentTool: " + web);
    }

    @Test
    void mcpApiMeta_carriesPartialToolWithOnlyExposedMethod() throws Exception {
        var partial = build().mcpApiMeta().getApis().stream()
                .filter(a -> "PartialTool".equals(a.getName())).findFirst().orElseThrow();
        var methodNames = partial.getMethods().stream().map(m -> m.getName()).toList();

        assertTrue(methodNames.contains("exposed"), "tools/list source lists the exposed method");
        assertFalse(methodNames.contains("hidden"),
                "tools/list source must omit the un-annotated method: " + methodNames);
    }

    @Test
    void mcpApiMeta_omitsFullyUnexposedWebService() throws Exception {
        var names = build().mcpApiMeta().getApis().stream().map(a -> a.getName()).toList();

        assertFalse(names.contains("WebEcho"),
                "a service with no agentTool opt-in is absent from tools/list: " + names);
        // Non-vacuity: the services that DO opt in are present.
        assertTrue(names.containsAll(List.of("PartialTool", "AgentToolEcho")),
                "opted-in services present: " + names);
    }
}
