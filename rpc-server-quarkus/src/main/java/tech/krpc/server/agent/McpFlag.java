package tech.krpc.server.agent;

import tech.krpc.util.FlagResolution;
import tech.krpc.util.FlagSwitch;

/**
 * ADR-0003 (umbrella) known debt 3, fixed: the ONE resolution point for the hand-written half of the
 * MCP capability flag.
 *
 * <p>{@code /mcp} has two faces — {@link McpHandler} (POST) and {@link McpGetHandler} (GET) — and
 * each used to call {@code EnvUtils.env("KRPC_MCP", "false")} and parse the result itself. That was
 * two resolution points for one switch (requirement 4): nothing kept them in step, so an edit to one
 * face silently desynchronised it from the other. Both now gate on {@link #enabled()}.
 *
 * <p>The parsing was also wrong in two ways the happy path hid (requirements 1 and 2):
 * {@code EnvUtils.env} hands back {@code System.getenv}'s value verbatim, so {@code KRPC_MCP=" true "}
 * — a YAML block scalar, a Dockerfile line continuation, a terminal copy-paste — resolved to OFF with
 * nothing in the log to explain the missing endpoint; and its lookup was unguarded, so a failing read
 * propagated out of {@code enabled()} instead of falling to the safe side. {@link FlagResolution}
 * fixes both, and {@link FlagSwitch} adds requirement 5's single resolution log line.
 *
 * <p><b>Class B (ADR-0003), default OFF — and that stays.</b> Turning MCP on adds a network surface,
 * so "off" is the correct behaviour and the flag is an explicit opt-in (NS-6; krpc ADR-0004 /
 * ADR-0007). The safe side for an unrecognised or unreadable value is therefore also OFF: the cost
 * is one legitimate opt-in refused, visibly, in the log — against exposing {@code /mcp} unasked.
 *
 * <p><b>Only the env half lives here.</b> {@code rpc.server.mcp.enabled} is injected by the
 * container ({@code @ConfigProperty} on each handler), which resolves after image build; ADR-0003
 * exempts that read site and it is deliberately not read again here.
 */
final class McpFlag {

    /** Documented environment form of the MCP switch. */
    static final String ENV_ENABLED = "KRPC_MCP";

    /** ADR-0003 class B: a capability surface is not exposed unless it is asked for. */
    private static final boolean DEFAULT_ENABLED = false;

    /** Requirement 2's safe side for class B: unrecognised or unreadable keeps {@code /mcp} absent. */
    private static final boolean SAFE_ENABLED = false;

    // ADR-0003 requirement 3: the memo cell only. Both handlers read this flag from an instance
    // method on the first-use path, and it must stay that way — resolving here (or in a holder
    // <clinit>) would bake the build machine's environment into a native image.
    private static final FlagSwitch ENABLED = new FlagSwitch(ENV_ENABLED, DEFAULT_ENABLED);

    private McpFlag() {}

    /**
     * The env half of the MCP gate, resolved once on first use and logged once with its source.
     * Both {@code /mcp} faces call this method; neither parses {@code KRPC_MCP} itself.
     */
    static boolean enabled() {
        Boolean memo = ENABLED.resolved();
        return memo != null ? memo : ENABLED.publish(read(ENV_ENABLED));
    }

    /**
     * Guarded read (package-private so the unit contract can drive the failure branch with a name the
     * JDK rejects): requirement 2 — a lookup that throws resolves to the safe side instead of
     * propagating out of a handler's {@code enabled()} during route registration.
     */
    static FlagResolution read(String envName) {
        try {
            return resolve(System.getenv(envName));
        } catch (RuntimeException failure) {
            return FlagResolution.readFailure(SAFE_ENABLED, failure);
        }
    }

    /**
     * Pure resolver (package-private for the unit contract): requirements 1 and 2 with this flag's
     * own default (OFF) and safe side (OFF). No property source — see the class doc.
     */
    static FlagResolution resolve(String env) {
        return FlagResolution.of(DEFAULT_ENABLED, SAFE_ENABLED, null, env);
    }
}
