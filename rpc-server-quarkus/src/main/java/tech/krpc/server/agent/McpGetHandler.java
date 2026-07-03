package tech.krpc.server.agent;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AsciiString;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.krpc.http.server.AbstractHttpHandler;
import tech.krpc.http.server.AsciiHeader;
import tech.krpc.http.server.GetHandler;
import tech.krpc.util.EnvUtils;

/**
 * ADR-0004 (AGENT-001 P1): {@code GET /mcp} → HTTP 405.
 *
 * <p>MCP Streamable HTTP (spec 2025-06-18) says a server that does not offer an SSE stream
 * on the MCP endpoint MUST answer GET with {@code 405 Method Not Allowed} (not 404). This
 * bridge is JSON-response mode only (no SSE), so GET is always 405. Same flag gate as
 * {@link McpHandler} — absent when MCP is OFF, so an OFF server keeps returning 404 for the
 * unregistered path (byte-level zero surface); a 405 only appears once MCP is enabled.
 */
@Unremovable
@ApplicationScoped
public class McpGetHandler implements GetHandler {

    private static final AsciiString STATUS_HEADER =
            AsciiString.cached(AbstractHttpHandler.STATUS_OVERRIDE_HEADER);
    private static final AsciiString ALLOW = AsciiString.cached("allow");

    @ConfigProperty(name = "rpc.server.mcp.enabled", defaultValue = "false")
    boolean mcpEnabled;

    @Override
    public String path() {
        return "/mcp";
    }

    @Override
    public String contextType() {
        return AbstractHttpHandler.TYPE_JSON;
    }

    @Override
    public boolean enabled() {
        if (mcpEnabled) {
            return true;
        }
        var env = EnvUtils.env("KRPC_MCP", "false");
        return "true".equalsIgnoreCase(env) || "1".equals(env);
    }

    @Override
    public byte[] handle(QueryStringDecoder param, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
        resHeader.add(new AsciiHeader(STATUS_HEADER, "405"));
        resHeader.add(new AsciiHeader(ALLOW, "POST"));
        return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"GET not supported; use POST (no SSE stream on this endpoint)\"}}"
                .getBytes(StandardCharsets.UTF_8);
    }
}
