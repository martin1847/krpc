package tech.krpc.server.agent;

import tech.krpc.annotation.RpcService;
import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.model.RpcResult;

/**
 * Minimal service fixtures for AGENT-001 tests:
 * <ul>
 *   <li>{@link WebEchoService} — {@code @UnsafeWeb} (agentTool defaults false): web-exposed
 *       but NOT an MCP tool.</li>
 *   <li>{@link AgentToolEchoService} — {@code @UnsafeWeb(agentTool = true)}: web-exposed AND
 *       an MCP tool.</li>
 *   <li>{@link HiddenAdminService} — no {@code @UnsafeWeb}: hidden (RefUtils.rpcServiceName
 *       prefixes it with '-'), on neither surface.</li>
 * </ul>
 * The pair (WebEcho vs AgentToolEcho) is what lets a test prove the agentTool gate blocks a
 * REAL {@code @UnsafeWeb} method, not merely an unknown tool name.
 */
final class AgentTestServices {

    private AgentTestServices() {
    }

    @UnsafeWeb
    @RpcService(description = "web echo service")
    public interface WebEchoService {
        RpcResult<String> echo(String in);
    }

    @UnsafeWeb(agentTool = true)
    @RpcService(description = "agent-tool echo service")
    public interface AgentToolEchoService {
        RpcResult<String> tool(String in);
    }

    @RpcService(description = "internal-only service")
    public interface HiddenAdminService {
        RpcResult<String> secret(String in);
    }

    /**
     * AGENT-002: interface is {@code @UnsafeWeb} (agentTool defaults false), so only the
     * methods annotated {@code @UnsafeWeb.AgentTool} are MCP tools — a per-method subset. Here
     * {@code exposed} is a tool; {@code hidden} stays web-only.
     */
    @UnsafeWeb
    @RpcService(description = "partial agent-tool service")
    public interface PartialToolService {
        @UnsafeWeb.AgentTool
        RpcResult<String> exposed(String in);

        RpcResult<String> hidden(String in);
    }

    public static class WebEcho implements WebEchoService {
        @Override
        public RpcResult<String> echo(String in) {
            return RpcResult.ok(in);
        }
    }

    public static class AgentToolEcho implements AgentToolEchoService {
        @Override
        public RpcResult<String> tool(String in) {
            return RpcResult.ok(in);
        }
    }

    public static class HiddenAdmin implements HiddenAdminService {
        @Override
        public RpcResult<String> secret(String in) {
            return RpcResult.ok("secret:" + in);
        }
    }

    public static class PartialTool implements PartialToolService {
        @Override
        public RpcResult<String> exposed(String in) {
            return RpcResult.ok("exposed:" + in);
        }

        @Override
        public RpcResult<String> hidden(String in) {
            return RpcResult.ok("hidden:" + in);
        }
    }
}
