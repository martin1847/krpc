package tech.krpc.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.netty.handler.codec.http.HttpHeaders;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.krpc.util.JsonUtils;

/**
 * HARDEN-B4 O6 — virtual-thread dispatch contract for {@link AbstractHttpHandler#writeHandler}.
 *
 * <p>After O6, {@code writeHandler} is asynchronous: it drops {@code autoRead}, dispatches
 * {@code handler.handle(...)} to the {@code HANDLER_VT} virtual-thread executor, writes the response
 * back on the channel eventLoop, and re-arms {@code autoRead} in a finally. An {@link
 * io.netty.channel.embedded.EmbeddedChannel} {@code writeInbound}/{@code readOutbound} therefore
 * returns null for handler paths (the VT + eventLoop tasks have not run), so these tests drive a
 * REAL in-process bound {@link HttpServer} with {@link java.net.http.HttpClient} and defend the
 * externally observable async contract:
 *
 * <ul>
 *   <li>A — {@code handle()} runs on a VIRTUAL thread (proves it left the netty worker eventLoop).
 *   <li>B — a handler {@code RuntimeException} maps to a neutral 500 JSON envelope, no message leak
 *       (re-homed from HttpErrorMappingTest case 6, whose EmbeddedChannel driver can no longer reach
 *       the async error path).
 *   <li>C — a slow (~1.5s) handler does not starve a concurrent fast request (the core O6 property).
 *   <li>D — {@code autoRead} is re-armed, so a keep-alive connection serves a second request.
 * </ul>
 */
class HttpVirtualThreadDispatchTest {

    /** How long the SLOW handler blocks inside {@code handle()} (on its virtual thread). */
    private static final long SLOW_SLEEP_MS = 1500L;

    /** Generous ceiling for the FAST round-trip while SLOW is mid-sleep — well under SLOW_SLEEP_MS. */
    private static final long FAST_MAX_MS = 800L;

    private int port;
    private VtTestHandler handler;
    private HttpServer server;
    private HttpClient client;

    @BeforeEach
    void startServer() throws Exception {
        // Grab an OS-assigned free port, release it, then bind the real server on it (accept the
        // tiny race — the standard in-process server-test pattern, same as HttpServerBindLeakTest).
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        handler = new VtTestHandler(SLOW_SLEEP_MS);
        server = new HttpServer(handler, port);
        server.start();
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void stopServer() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    // ---- A: dispatch happens on a virtual thread, not the netty worker eventLoop --------------

    @Test
    void dispatchRunsOnVirtualThread() throws Exception {
        HttpResponse<String> r = post("/probe");

        assertEquals(200, r.statusCode());
        assertEquals("true", r.body(),
                "handle() must run on a virtual thread (isVirtual), not the netty worker eventLoop");
    }

    // ---- B: handler RuntimeException -> 500 neutral JSON, no getMessage() leak (migrated case 6) --

    @Test
    void handlerInternalError_maps500NeutralWithoutLeak() throws Exception {
        HttpResponse<String> r = post("/boom");

        assertEquals(500, r.statusCode());
        assertTrue(contentType(r).contains("application/json"),
                "500 must be a JSON envelope; got content-type " + contentType(r));

        Map<String, Object> env = envelope(r.body());
        assertEquals(500, code(env), "envelope code must be 500");
        assertEquals("Internal Server Error", env.get("message"),
                "500 message must be the neutral status reason phrase, not the exception text");
        assertFalse(r.body().contains("SECRET-INTERNAL-xyz"),
                "the internal exception message must NOT leak to the client");
    }

    // ---- C: a slow handler must not starve concurrent fast requests (the core O6 property) -----

    @Test
    void slowHandlerDoesNotStarveFastRequests() throws Exception {
        // Fire SLOW asynchronously; it sleeps SLOW_SLEEP_MS on its own virtual thread. Wait until it
        // has actually begun dispatching (deterministic latch) so the timing below is not a guess.
        CompletableFuture<HttpResponse<String>> slowFut =
                client.sendAsync(request("/slow"), HttpResponse.BodyHandlers.ofString());
        assertTrue(handler.slowStarted.await(2, TimeUnit.SECONDS),
                "SLOW handler never began dispatching within 2s");

        long t0 = System.nanoTime();
        HttpResponse<String> fast = client.send(request("/fast"), HttpResponse.BodyHandlers.ofString());
        long fastMs = (System.nanoTime() - t0) / 1_000_000L;

        assertEquals(200, fast.statusCode());
        assertFalse(slowFut.isDone(),
                "SLOW must still be pending when FAST returns — else the fast path waited on it");
        assertTrue(fastMs < FAST_MAX_MS,
                "FAST round-trip was " + fastMs + "ms; expected < " + FAST_MAX_MS
                        + "ms while SLOW sleeps " + SLOW_SLEEP_MS + "ms");

        // Drain SLOW so teardown is clean and the assertion above is not left dangling.
        HttpResponse<String> slow = slowFut.get(5, TimeUnit.SECONDS);
        assertEquals(200, slow.statusCode());
    }

    // ---- D: autoRead re-arms, so a keep-alive connection serves a second request ---------------

    @Test
    void autoReadReArmsForKeepAliveReuse() {
        // One default HTTP/1.1 client => keep-alive: the two requests reuse the same connection. If
        // setAutoRead(true) were not re-enabled after the first response, the server would never read
        // the second request and it would hang (caught by the preemptive timeout).
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            HttpResponse<String> first = client.send(request("/fast"), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, first.statusCode());
            assertEquals("{\"ok\":true}", first.body());

            HttpResponse<String> second = client.send(request("/fast"), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, second.statusCode());
            assertEquals("{\"ok\":true}", second.body());
        }, "second keep-alive request hung — autoRead was not re-armed after the first response");
    }

    // =====================================================================================
    // Harness
    // =====================================================================================

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
    }

    private HttpResponse<String> post(String path) throws Exception {
        return client.send(request(path), HttpResponse.BodyHandlers.ofString());
    }

    private static String contentType(HttpResponse<String> r) {
        return r.headers().firstValue("content-type").orElse("");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envelope(String body) {
        return JsonUtils.parse(body, Map.class);
    }

    private static int code(Map<String, Object> env) {
        return ((Number) env.get("code")).intValue();
    }

    // ---- Test handler + the four registered endpoints -------------------------------------

    /**
     * Package-private concrete {@link AbstractHttpHandler}: registers FAST / SLOW / THREAD-PROBE /
     * BOOM POST endpoints. {@code slowStarted} lets a test observe that SLOW has begun dispatching
     * before it times the FAST round-trip (assertion C), so the timing is deterministic.
     */
    static final class VtTestHandler extends AbstractHttpHandler {
        final CountDownLatch slowStarted = new CountDownLatch(1);
        private final long slowSleepMillis;

        VtTestHandler(long slowSleepMillis) {
            this.slowSleepMillis = slowSleepMillis;
            postMap.put("/fast", new FastHandler());
            postMap.put("/probe", new ProbeHandler());
            postMap.put("/slow", new SlowHandler());
            postMap.put("/boom", new BoomHandler());
        }

        @Override
        public Validator getValidator() {
            return null;
        }

        @Override
        public void initHandler() {
            // endpoints are registered via the constructor
        }

        /** Returns immediately — the low-latency reference used by C (fast) and D (keep-alive). */
        private static final class FastHandler implements PostHandler<String> {
            @Override
            public Class<String> getParamClass() {
                return String.class;
            }

            @Override
            public String path() {
                return "/fast";
            }

            @Override
            public boolean useValidator() {
                return false;
            }

            @Override
            public byte[] handle(String param, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
                return "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String contextType() {
                return AbstractHttpHandler.TYPE_JSON;
            }
        }

        /** Reports whether {@code handle()} ran on a virtual thread — the teeth for assertion A. */
        private static final class ProbeHandler implements PostHandler<String> {
            @Override
            public Class<String> getParamClass() {
                return String.class;
            }

            @Override
            public String path() {
                return "/probe";
            }

            @Override
            public boolean useValidator() {
                return false;
            }

            @Override
            public byte[] handle(String param, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
                return String.valueOf(Thread.currentThread().isVirtual())
                        .getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String contextType() {
                return AbstractHttpHandler.TYPE_PLAIN;
            }
        }

        /** Blocks for {@code slowSleepMillis}; signals {@code slowStarted} first (assertion C). */
        private final class SlowHandler implements PostHandler<String> {
            @Override
            public Class<String> getParamClass() {
                return String.class;
            }

            @Override
            public String path() {
                return "/slow";
            }

            @Override
            public boolean useValidator() {
                return false;
            }

            @Override
            public byte[] handle(String param, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
                slowStarted.countDown();
                try {
                    Thread.sleep(slowSleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "{\"slow\":true}".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String contextType() {
                return AbstractHttpHandler.TYPE_JSON;
            }
        }

        /** Throws inside {@code handle()} with a sentinel message — the teeth for assertion B. */
        private static final class BoomHandler implements PostHandler<String> {
            @Override
            public Class<String> getParamClass() {
                return String.class;
            }

            @Override
            public String path() {
                return "/boom";
            }

            @Override
            public boolean useValidator() {
                return false;
            }

            @Override
            public byte[] handle(String param, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
                throw new RuntimeException("SECRET-INTERNAL-xyz");
            }

            @Override
            public String contextType() {
                return AbstractHttpHandler.TYPE_JSON;
            }
        }
    }
}
