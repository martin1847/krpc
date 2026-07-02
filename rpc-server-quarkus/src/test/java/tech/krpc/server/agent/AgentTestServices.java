package tech.krpc.server.agent;

import tech.krpc.annotation.RpcService;
import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.model.RpcResult;

/**
 * Minimal service fixtures for AGENT-001 P0 tests: one web (@UnsafeWeb) service and one
 * non-web (hidden) service. RefUtils.rpcServiceName prefixes hidden services with '-'.
 */
final class AgentTestServices {

    private AgentTestServices() {
    }

    @UnsafeWeb
    @RpcService(description = "web echo service")
    public interface WebEchoService {
        RpcResult<String> echo(String in);
    }

    @RpcService(description = "internal-only service")
    public interface HiddenAdminService {
        RpcResult<String> secret(String in);
    }

    public static class WebEcho implements WebEchoService {
        @Override
        public RpcResult<String> echo(String in) {
            return RpcResult.ok(in);
        }
    }

    public static class HiddenAdmin implements HiddenAdminService {
        @Override
        public RpcResult<String> secret(String in) {
            return RpcResult.ok("secret:" + in);
        }
    }
}
