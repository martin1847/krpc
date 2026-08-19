package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import tech.krpc.util.FlagResolution.Source;

/**
 * ADR-0003 (umbrella) known debt 3 — the hand-written half of {@code KRPC_MCP} resolves through ONE
 * accessor, with requirement 1/2 value semantics.
 *
 * <p>Two things are pinned here, and they fail for different reasons:
 * <ul>
 *   <li><b>The value matrix</b> ({@link McpFlag#resolve}/{@link McpFlag#read}) — {@code " true "} out
 *       of a YAML block scalar or a Dockerfile line continuation is ON (it used to be silently OFF),
 *       an unrecognised or unreadable value keeps the capability unexposed (class B safe side).</li>
 *   <li><b>The desync guard</b> — neither {@code /mcp} face may read or parse the environment itself.
 *       This is the failure ADR-0003 records as the third instance of the split switch: two handlers,
 *       two parsers, nothing keeping them in step. Checked structurally (the compiled handler must
 *       reference {@code McpFlag} and must NOT reference an env read), because the behavioural
 *       symptom only appears in a process whose {@code KRPC_MCP} is actually set — that is the job of
 *       the end-to-end status-code matrix, which cannot run inside this JVM.</li>
 * </ul>
 */
class McpFlagTest {

    /** Requirement 1's ON side — including the whitespace form that used to resolve OFF. */
    @Test
    void recognisedTruthyEnvEnablesMcp_trimmedAndCaseInsensitive() {
        for (String raw : new String[] {"true", "TRUE", " true ", "\ttrue\n", "1", " 1 "}) {
            var resolution = McpFlag.resolve(raw);
            assertTrue(resolution.value(), () -> "KRPC_MCP=\"" + raw + "\" must enable /mcp");
            assertEquals(Source.ENV, resolution.source());
        }
    }

    /** Class B default: nothing configured means the capability is not exposed (NS-6). */
    @Test
    void unconfiguredEnvKeepsMcpOff_byDefaultNotByAccident() {
        for (String raw : new String[] {null, "", "   ", "\n"}) {
            var resolution = McpFlag.resolve(raw);
            assertFalse(resolution.value(), "an unconfigured KRPC_MCP must leave /mcp absent");
            assertEquals(Source.DEFAULT, resolution.source(),
                    "blank is 'not configured', so the log must say the default stood");
        }
    }

    @Test
    void explicitFalseOrZeroKeepsMcpOff() {
        for (String raw : new String[] {"false", "FALSE", " false ", "0", " 0 "}) {
            var resolution = McpFlag.resolve(raw);
            assertFalse(resolution.value(), () -> "KRPC_MCP=\"" + raw + "\" must keep /mcp off");
            assertEquals(Source.ENV, resolution.source());
        }
    }

    /** Requirement 2: for a class-B capability the safe side is staying unexposed. */
    @Test
    void unrecognisedEnvKeepsMcpOff_andSaysWhat() {
        for (String raw : new String[] {"yes", "ture", "on", "enabled", "2"}) {
            var resolution = McpFlag.resolve(raw);
            assertFalse(resolution.value(),
                    () -> "KRPC_MCP=\"" + raw + "\" must not open /mcp on a guess");
            assertEquals(Source.UNRECOGNIZED, resolution.source());
            assertEquals("unrecognized(" + raw + ")", resolution.describe(),
                    "an operator who mistyped the opt-in must find the reason in the log");
        }
    }

    /**
     * Requirement 2: the lookup is guarded. Driven with the name {@code System.getenv} rejects
     * ({@code null} -> {@link NullPointerException}), which exercises the real {@code catch} in
     * {@link McpFlag#read} — the old {@code EnvUtils.env} call let a failure propagate out of a
     * handler's {@code enabled()} during route registration.
     */
    @Test
    void lookupFailureKeepsMcpOffInsteadOfPropagating() {
        var resolution = McpFlag.read(null);
        assertFalse(resolution.value(), "a failed read must leave the capability unexposed");
        assertEquals(Source.READ_FAILURE, resolution.source());
        assertEquals("read-failure(NullPointerException)", resolution.describe());
    }

    /** The env half is the accessor's own business: no property source is read here (see ADR-0003). */
    @Test
    void theEnvHalfNeverReadsThePropertyHalf() {
        assumeTrue(System.getenv(McpFlag.ENV_ENABLED) == null,
                "KRPC_MCP is set in this environment; the property-isolation check is moot");
        System.setProperty("rpc.server.mcp.enabled", "true");
        try {
            assertFalse(McpFlag.read(McpFlag.ENV_ENABLED).value(),
                    "rpc.server.mcp.enabled is the container-injected half — the hand-written"
                    + " accessor must not resolve it a second time");
        } finally {
            System.clearProperty("rpc.server.mcp.enabled");
        }
    }

    /**
     * ADR-0003 requirement 4 for the two faces of {@code /mcp}: one accessor, no local parsing. A
     * handler that goes back to reading {@code KRPC_MCP} itself (or hardcodes the gate) loses the
     * {@code McpFlag} reference and/or gains an env read, and this fails.
     */
    @Test
    void neitherMcpFaceResolvesTheFlagItself() {
        for (Class<?> face : List.of(McpHandler.class, McpGetHandler.class)) {
            String constants = constantPool(face);
            assertTrue(constants.contains("tech/krpc/server/agent/McpFlag"),
                    () -> face.getSimpleName() + " must gate on the shared McpFlag accessor");
            assertFalse(constants.contains("EnvUtils"),
                    () -> face.getSimpleName() + " must not read the environment itself"
                          + " (ADR-0003 known debt 3: that is what desynchronised POST from GET)");
            assertFalse(constants.contains("getenv"),
                    () -> face.getSimpleName() + " must not call System.getenv itself");
        }
    }

    /** Non-vacuity for the structural guard: both faces really do agree at runtime. */
    @Test
    void bothMcpFacesAgreeOnTheGate() {
        McpHandler post = new McpHandler();
        McpGetHandler get = new McpGetHandler();

        post.mcpEnabled = false;
        get.mcpEnabled = false;
        assertEquals(post.enabled(), get.enabled(),
                "POST /mcp and GET /mcp must never disagree about whether the endpoint exists");

        post.mcpEnabled = true;
        get.mcpEnabled = true;
        assertTrue(post.enabled(), "the injected property still opens POST /mcp");
        assertTrue(get.enabled(), "the injected property still opens GET /mcp");
    }

    /** The compiled class's raw bytes: its constant pool names every type and method it touches. */
    private static String constantPool(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, () -> "cannot read compiled class " + resource);
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new AssertionError("cannot read compiled class " + resource, e);
        }
    }
}
