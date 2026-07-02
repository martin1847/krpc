package tech.krpc.examples.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * Wave 3 Phase A — container-level regression guard for the agent HTTP endpoints
 * (AGENT-001 P0, ADR-0004).
 *
 * <p>This boots the quickstart — the exact "default consumer" the Wave 2 caveat named — with
 * {@code @QuarkusTest} under <b>DEFAULT Arc config</b> (no {@code quarkus.arc.remove-unused-beans=none}
 * anywhere; setting that would mask the very bug this test defends). {@code AgentDiscoverHandler} /
 * {@code AgentInvokeHandler} are discovered reflectively by {@code HttpHandlerExpose} via
 * {@code getBeans(Object.class, @Any)}, so without {@code @io.quarkus.arc.Unremovable} on them Arc's
 * default {@code remove-unused-beans=all} strips them, {@code HttpHandlerExpose} logs
 * "Skip HTTP Server , no Handlers found." and never binds the netty server — every assertion below
 * then goes RED with a connection refused.
 *
 * <p>The agent endpoints ride a standalone {@code tech.krpc.http.server.HttpServer} netty transport
 * on {@code http.port} (default 8080), which is a <b>different</b> server from the Quarkus HTTP port,
 * so the requests use a raw {@link HttpClient} against {@code http://localhost:8080} rather than
 * RestAssured (which would target the Quarkus port).
 */
@QuarkusTest
class AgentEndpointsQuarkusTest {

    /** Standalone netty agent server port (HttpHandlerExpose http.port default); NOT the Quarkus port. */
    private static final String AGENT_BASE = "http://localhost:8080";

    // HTTP/1.1 explicitly: the netty HttpServer pipeline is HttpRequestDecoder/HttpResponseEncoder
    // (plain HTTP/1.1), so no h2 upgrade negotiation.
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Test
    void discover_listsHelloServiceAndMethod() throws Exception {
        HttpResponse<String> res = send(HttpRequest.newBuilder()
                .uri(URI.create(AGENT_BASE + "/agent/discover"))
                .GET()
                .build());

        // Goes RED without @Unremovable: the netty server never starts -> connection refused.
        assertEquals(200, res.statusCode(), () -> "discover body: " + res.body());
        String body = res.body();
        // ApiMeta JSON exposes the @UnsafeWeb service (name "Hello") and its method ("hello").
        assertTrue(body.contains("\"Hello\""), () -> "discover missing Hello service: " + body);
        assertTrue(body.contains("\"hello\""), () -> "discover missing hello method: " + body);
    }

    @Test
    void invoke_helloReturnsGreeting() throws Exception {
        HttpResponse<String> res = send(HttpRequest.newBuilder()
                .uri(URI.create(AGENT_BASE + "/agent/invoke"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"service\":\"Hello\",\"method\":\"hello\",\"input\":{\"name\":\"krpc\"}}"))
                .build());

        // Errors ride the JSON `code`, not the HTTP status line: success is HTTP 200 + code:0.
        assertEquals(200, res.statusCode(), () -> "invoke body: " + res.body());
        String body = res.body();
        assertTrue(body.contains("\"code\":0"), () -> "invoke not code:0: " + body);
        // Business payload lands under `data`; HelloServiceImpl returns "Hello, " + name + "!".
        assertTrue(body.contains("Hello, krpc!"),
                () -> "invoke data message missing greeting: " + body);
    }

    @Test
    void invoke_unknownServiceIsNotFoundCode5() throws Exception {
        HttpResponse<String> res = send(HttpRequest.newBuilder()
                .uri(URI.create(AGENT_BASE + "/agent/invoke"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"service\":\"Ghost\",\"method\":\"x\"}"))
                .build());

        // Unknown/hidden service -> gRPC NOT_FOUND (code 5) in the JSON body, still HTTP 200.
        assertEquals(200, res.statusCode(), () -> "unknown-service body: " + res.body());
        assertTrue(res.body().contains("\"code\":5"),
                () -> "unknown service not code:5: " + res.body());
    }

    /**
     * The netty agent server binds synchronously in {@code HttpServer.start()} during {@code @Startup},
     * so it is normally up before any test runs. Guard against a rare connect race with a bounded
     * retry (≤5s total) — never an unconditional skip: the endpoint MUST ultimately answer.
     */
    private HttpResponse<String> send(HttpRequest request) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException connectFailure) {
                // Connection refused / reset before the netty server finished binding: retry briefly.
                last = connectFailure;
                Thread.sleep(100);
            }
        }
        fail("agent endpoint at " + AGENT_BASE + " never answered within 5s "
                + "(is the netty HttpServer started? @Unremovable stripped?): " + last);
        throw new AssertionError("unreachable");
    }
}
