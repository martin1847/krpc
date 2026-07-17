package tech.krpc.server.agent;

import java.util.HashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import tech.krpc.common.meta.ApiMeta;
import tech.krpc.server.WebInvoker;

/**
 * ADR-0004 (AGENT-001 P1): the MCP tool surface — the {@code @UnsafeWeb(agentTool=true)}
 * subset of the web dispatch surface.
 *
 * <p>Deliberately separate from {@link WebMethodRegistry}: {@code tools/call} resolves
 * <em>only</em> against this map, so a non-agentTool web method is unreachable over MCP
 * even though it is reachable over {@code /agent/invoke}. This is the agentTool gate (the
 * third exposure filter, on top of the two hidden-service filters). Dispatch still runs
 * through the identical {@link WebInvoker#invokeWeb} path as {@code /agent/invoke}, so the
 * credential check is never bypassed.
 */
@ApplicationScoped
public class McpToolRegistry {

    /// MCP tool name ("Service_method") -> web dispatcher, agentTool subset only
    private Map<String, WebInvoker> tools = Map.of();

    /// ApiMeta containing only agentTool services (the tools/list source)
    private ApiMeta toolApiMeta;

    /**
     * MCP tool name for a service/method. Underscore-joined so it matches the
     * {@code ^[a-zA-Z0-9_-]+$} charset real MCP clients enforce (a raw {@code '/'} is
     * rejected by common clients). krpc service names are CamelCase Java simple names and
     * methods are camelCase identifiers, so this is stable and effectively collision-free.
     */
    public static String toolName(String service, String method) {
        return service + "_" + method;
    }

    /**
     * @param webKeyed "Service/method" -> dispatcher (the agentTool subset from
     *                 RpcServerBuilder.mcpMethods()); rekeyed here to MCP tool names.
     */
    public void init(Map<String, WebInvoker> webKeyed, ApiMeta toolApiMeta) {
        var rekeyed = new HashMap<String, WebInvoker>(webKeyed.size());
        for (var e : webKeyed.entrySet()) {
            // key is "Service/method" (single slash, app/ already stripped).
            int slash = e.getKey().indexOf('/');
            var name = slash < 0 ? e.getKey()
                    : toolName(e.getKey().substring(0, slash), e.getKey().substring(slash + 1));
            rekeyed.put(name, e.getValue());
        }
        this.tools = rekeyed;
        this.toolApiMeta = toolApiMeta;
    }

    /**
     * @return the dispatcher for MCP {@code toolName}, or {@code null} if it is not an
     * agentTool method (unknown, hidden, or web-but-not-agentTool). Callers must treat
     * {@code null} as an unknown tool (JSON-RPC error -32602).
     */
    public WebInvoker lookup(String toolName) {
        if (null == toolName) {
            return null;
        }
        return tools.get(toolName);
    }

    /**
     * @return the live set of MCP tool names ("Service_method"). Used for did-you-mean
     * suggestions on an unknown-tool error; never null (empty when the face is empty).
     */
    public java.util.Set<String> toolNames() {
        return tools.keySet();
    }

    public ApiMeta apiMeta() {
        return toolApiMeta;
    }
}
