package tech.krpc.examples.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Wave 3 Phase B — the "zero new surface when OFF" half of the MCP-bridge contract (ADR-0004).
 *
 * <p>Runs on the DEFAULT profile (no {@code @TestProfile}) = MCP off, so it shares the boot with
 * {@code AgentEndpointsQuarkusTest} (Quarkus groups same-profile classes into one app boot). The
 * flag gate in {@code HttpHandlerExpose} skips a disabled handler, so {@code /mcp} is never put in
 * the netty {@code postMap} and the router falls through to its 404 — a genuine absence, not a
 * runtime error path. This is a SEPARATE class from the ON handshake because Quarkus reboots per
 * profile; keeping the two profiles apart is required.
 *
 * <p>Kept out of {@code AgentEndpointsQuarkusTest} so that class is not touched/weakened.
 */
@QuarkusTest
class McpDisabledQuarkusTest {

    /** Standalone netty agent server port (HttpHandlerExpose http.port default); NOT the Quarkus port. */
    private static final String MCP_URL = "http://localhost:8080/mcp";

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Test
    void mcpPathAbsentWhenDisabled() throws Exception {
        // The netty server IS up (it serves /agent/*); only the /mcp handler is unregistered when
        // the flag is off, so the router returns its not-found response for POST /mcp.
        HttpResponse<String> res = send(HttpRequest.newBuilder()
                .uri(URI.create(MCP_URL))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
                .build());

        // 404 (not 200 with a JSON-RPC result) proves the path was never registered — MCP truly off.
        assertEquals(404, res.statusCode(),
                () -> "MCP off should 404 the /mcp path, got: " + res.statusCode() + " / " + res.body());
    }

    /**
     * The netty agent server binds synchronously during {@code @Startup}. Bounded retry (≤5s) only
     * guards the connect race; a bound server answering 404 ends the loop on the first try.
     */
    private HttpResponse<String> send(HttpRequest request) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException connectFailure) {
                last = connectFailure;
                Thread.sleep(100);
            }
        }
        fail("netty agent server at " + MCP_URL + " never answered within 5s "
                + "(is the standalone HttpServer started?): " + last);
        throw new AssertionError("unreachable");
    }
}
