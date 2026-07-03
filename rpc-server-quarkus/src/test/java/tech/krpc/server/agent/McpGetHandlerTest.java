package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.junit.jupiter.api.Test;

import tech.krpc.http.server.AsciiHeader;
import tech.krpc.util.JsonUtils;

/**
 * ADR-0004 (AGENT-001 P1): {@link McpGetHandler} unit tests — plain JUnit5, no Quarkus boot.
 *
 * <p>Advisory #2 transport contract: MCP Streamable HTTP (spec 2025-06-18) requires a
 * JSON-response-only endpoint to answer GET with {@code 405 Method Not Allowed} plus an
 * {@code Allow: POST} header (never 404, never 200). {@code handle()} sets the status via the
 * framework's STATUS_OVERRIDE header ({@code x-krpc-http-status}) and returns a JSON-RPC error
 * body. The {@code enabled()} flag gate is HttpHandlerExpose's job, so we drive {@code handle()}
 * directly.
 */
class McpGetHandlerTest {

    /** Case-insensitive lookup of a response header by name; null if absent. */
    private static AsciiHeader header(List<AsciiHeader> headers, String name) {
        for (var h : headers) {
            if (h.name.contentEqualsIgnoreCase(name)) {
                return h;
            }
        }
        return null;
    }

    @Test
    @SuppressWarnings("unchecked")
    void get_alwaysMethodNotAllowed_withAllowPostAndJsonRpcErrorBody() {
        var handler = new McpGetHandler();
        var resHeader = new ArrayList<AsciiHeader>();
        byte[] out = handler.handle(
                new QueryStringDecoder("/mcp"), resHeader, new DefaultHttpHeaders());

        // Spec: GET on a JSON-only MCP endpoint is 405 (not 404), carried by STATUS_OVERRIDE.
        var status = header(resHeader, "x-krpc-http-status");
        assertNotNull(status, "GET must set the HTTP-status override header: " + resHeader);
        assertEquals("405", status.value, "GET /mcp maps to HTTP 405 Method Not Allowed");

        // Spec: a 405 MUST advertise the allowed methods; this endpoint is POST-only.
        var allow = header(resHeader, "allow");
        assertNotNull(allow, "405 must carry an Allow header: " + resHeader);
        assertEquals("POST", allow.value, "the MCP endpoint only accepts POST");

        // The body is a JSON-RPC error object, not empty and not a success result.
        var parsed = JsonUtils.parse(new String(out, StandardCharsets.UTF_8), Object.class);
        assertInstanceOf(Map.class, parsed, "body is a JSON-RPC object: " + parsed);
        var env = (Map<String, Object>) parsed;
        assertInstanceOf(Map.class, env.get("error"),
                "GET yields a JSON-RPC error object (has \"error\"): " + env);
    }
}
