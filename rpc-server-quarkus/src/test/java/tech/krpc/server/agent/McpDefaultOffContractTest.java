package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Field;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.util.EnvUtils;
import org.junit.jupiter.api.Test;

/**
 * NS-6 — the agent surface is opt-in; its flags default OFF.
 *
 * <p>The umbrella NORTH_STAR NS-6 requires the agent/MCP surface to be opt-in with flags
 * defaulting OFF (ADR-0004 AGENT-001: "byte-level zero new surface" until enabled). This
 * test pins the PRODUCTION defaults (the values an unconfigured runtime observes), not Java
 * field defaults:
 *
 * <ul>
 *   <li>{@code @UnsafeWeb.agentTool()} defaults {@code false} (annotation default) — web
 *       exposure never implies MCP-tool exposure.</li>
 *   <li>{@link McpHandler}/{@link McpGetHandler} {@code mcpEnabled} carries
 *       {@code @ConfigProperty(name="rpc.server.mcp.enabled", defaultValue="false")}: an
 *       unconfigured Quarkus/SmallRye injection yields {@code false}. Asserted via
 *       reflection on the runtime-retained annotation (no CDI container needed), so flipping
 *       the production {@code defaultValue} to {@code "true"} turns this RED.</li>
 *   <li>{@code KRPC_MCP} unset resolves to {@code "false"} (OFF) at the env layer.</li>
 * </ul>
 *
 * <p>Tradeoff: this asserts the {@code @ConfigProperty.defaultValue()} (the injection
 * default) rather than booting a container to observe injection end-to-end — the minimal
 * honest bite without a {@code @QuarkusTest}. The {@code enabled()} true-flip and OFF-branch
 * checks remain as non-vacuity. Plain JUnit test in {@code tech.krpc.server.agent} (the
 * {@code mcpEnabled} fields are package-private); this is the module that owns and ENFORCES
 * the MCP flag gate.
 */
class McpDefaultOffContractTest {

    /** True only if the ambient {@code KRPC_MCP} is explicitly turned on (a branch test would be moot). */
    private static boolean krpcMcpEnvIsOn() {
        String e = System.getenv("KRPC_MCP");
        return e != null && ("true".equalsIgnoreCase(e) || "1".equals(e));
    }

    private static ConfigProperty mcpEnabledConfig(Class<?> handler) throws NoSuchFieldException {
        Field f = handler.getDeclaredField("mcpEnabled");
        ConfigProperty cp = f.getAnnotation(ConfigProperty.class);
        assertNotNull(cp, handler.getSimpleName() + ".mcpEnabled must carry @ConfigProperty");
        return cp;
    }

    @Test
    void agentToolDefaultsFalse() throws NoSuchMethodException {
        Object dflt = UnsafeWeb.class.getMethod("agentTool").getDefaultValue();
        assertEquals(Boolean.FALSE, dflt,
            "@UnsafeWeb.agentTool() must default false — MCP-tool exposure is opt-in, never"
            + " implied by @UnsafeWeb (web exposure) alone (NS-6 / ADR-0004).");
    }

    @Test
    void mcpPostHandlerConfigDefaultsOff() throws NoSuchFieldException {
        ConfigProperty cp = mcpEnabledConfig(McpHandler.class);
        assertEquals("rpc.server.mcp.enabled", cp.name(), "POST /mcp flag name");
        assertEquals("false", cp.defaultValue(),
            "rpc.server.mcp.enabled must default \"false\" — an unconfigured runtime injects OFF (POST /mcp)");
    }

    @Test
    void mcpGetHandlerConfigDefaultsOff() throws NoSuchFieldException {
        ConfigProperty cp = mcpEnabledConfig(McpGetHandler.class);
        assertEquals("rpc.server.mcp.enabled", cp.name(), "GET /mcp flag name");
        assertEquals("false", cp.defaultValue(),
            "rpc.server.mcp.enabled must default \"false\" — an unconfigured runtime injects OFF (GET /mcp)");
    }

    @Test
    void mcpPostHandlerGateFlips() {
        McpHandler h = new McpHandler();
        // Non-vacuity: the flag actually gates enabled() — flip it on and the route enables.
        h.mcpEnabled = true;
        assertTrue(h.enabled(), "rpc.server.mcp.enabled=true must enable POST /mcp");
        // OFF branch: flag false + KRPC_MCP not on -> disabled (route never registered).
        assumeTrue(!krpcMcpEnvIsOn(), "KRPC_MCP is turned on in this env; OFF-branch check is moot");
        h.mcpEnabled = false;
        assertFalse(h.enabled(), "POST /mcp must be OFF when the flag and KRPC_MCP are off");
    }

    @Test
    void mcpGetHandlerGateFlips() {
        McpGetHandler h = new McpGetHandler();
        h.mcpEnabled = true;
        assertTrue(h.enabled(), "rpc.server.mcp.enabled=true must enable GET /mcp");
        assumeTrue(!krpcMcpEnvIsOn(), "KRPC_MCP is turned on in this env; OFF-branch check is moot");
        h.mcpEnabled = false;
        assertFalse(h.enabled(), "GET /mcp must be OFF when the flag and KRPC_MCP are off");
    }

    @Test
    void krpcMcpEnvDefaultsOff() {
        assumeTrue(System.getenv("KRPC_MCP") == null,
            "KRPC_MCP is set in this env; the unset-default assertion is moot");
        assertEquals("false", EnvUtils.env("KRPC_MCP", "false"),
            "KRPC_MCP unset must resolve to \"false\" (agent surface OFF by default)");
    }
}
