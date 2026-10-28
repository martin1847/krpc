package tech.krpc.examples.quickstart;

import tech.krpc.annotation.Doc;
import tech.krpc.annotation.RpcService;
import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.model.RpcResult;

/**
 * The one rule that bites first (SPEC §1): every method returns
 * {@code RpcResult<Dto>} and takes at most one parameter.
 *
 * <p>{@code @UnsafeWeb} marks this service as reachable directly by browsers /
 * frontend / rpcurl over the HTTP gateway (SPEC §6). Without it the service is
 * service-to-service only and rpcurl over HTTP cannot reach it.
 *
 * <p>{@code agentTool=true} additionally opts this service into the MCP tool surface
 * (ADR-0004 P1): with {@code rpc.server.mcp.enabled=true} (env {@code KRPC_MCP}), the
 * {@code POST /mcp} endpoint lists {@code Hello_hello} as an MCP tool. agentTool is a
 * deliberate subset of web exposure — {@code @UnsafeWeb} alone does not create a tool.
 *
 * <p>Service name derivation (SPEC §5): {@code HelloService} -> {@code Hello},
 * so the call path is {@code quickstart/Hello/hello}.
 */
@UnsafeWeb(agentTool = true)
@RpcService(description = "KRPC quickstart demo service")
public interface HelloService {

    @Doc("Returns a greeting for the given name.")
    RpcResult<HelloReply> hello(HelloRequest req);
}
