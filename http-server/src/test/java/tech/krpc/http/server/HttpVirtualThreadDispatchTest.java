package tech.krpc.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
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
 * HARDEN-B4 O6 — virtual-thread dispatch + per-connection response-ordering contract for
 * {@link AbstractHttpHandler}.
 *
 * <p>After O6, request handling is asynchronous: {@code channelRead0} enqueues the request on a
 * per-connection FIFO, {@code handle(...)} runs on the {@code HANDLER_VT} virtual-thread executor,
 * and the response is written back on the channel eventLoop. An {@link
 * io.netty.channel.embedded.EmbeddedChannel} {@code writeInbound}/{@code readOutbound} therefore
 * returns null for handler paths (the VT + eventLoop tasks have not run), so these tests drive a
 * REAL in-process bound {@link HttpServer} with {@link java.net.http.HttpClient} (and, for E, a raw
 * socket) and defend the externally observable async contract:
 *
 * <ul>
 *   <li>A — {@code handle()} runs on a VIRTUAL thread (proves it left the netty worker eventLoop).
 *   <li>B — a handler {@code RuntimeException} maps to a neutral 500 JSON envelope, no message leak
 *       (re-homed from HttpErrorMappingTest case 6, whose EmbeddedChannel driver can no longer reach
 *       the async error path).
 *   <li>C — a slow (~1.5s) request on one connection does not block a fast request on ANOTHER
 *       connection (per-connection independence; renamed from an over-claimed "starvation" test —
 *       two connections may land on different eventLoops, so this does not prove same-eventLoop
 *       non-starvation; the off-eventLoop evidence is A, {@code dispatchRunsOnVirtualThread}).
 *   <li>D — {@code autoRead} is re-armed, so a keep-alive connection serves a second request.
 *   <li>E — two requests pipelined in a SINGLE TCP segment (slow then fast) return in REQUEST order
 *       with each body framed against its own request — the fix-round-1 guard for O6's data-crossing
 *       bug (before the per-connection FIFO the fast response overtook / mis-framed the slow one).
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

    // ---- C: a slow request on one connection does not block a fast request on ANOTHER ----------

    @Test
    void slowRequestDoesNotBlockAnotherConnection() throws Exception {
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

    // ---- E: pipelined requests in ONE TCP segment return in request order, correctly framed -----

    @Test
    void pipelinedRequestsReturnInRequestOrder() throws Exception {
        // The fix-round-1 guard for O6's data-crossing bug. Two HTTP/1.1 requests — SLOW then FAST —
        // are written back-to-back into a SINGLE socket write (one TCP segment): the aggregator
        // decodes BOTH before autoRead(false) can stop the read, so channelRead0 fires twice on the
        // eventLoop before either handler runs. Without the per-connection FIFO both dispatch to the
        // VT executor concurrently and FAST (returns instantly) writes before SLOW (~1.5s) — the
        // client would read {"ok":true} first, i.e. FAST's body framed as the response to SLOW.
        // With the FIFO, SLOW is served to completion before FAST is dispatched, so responses come
        // back strictly in request order, each body against its own request.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            byte[] pipelined = (rawPost("/slow") + rawPost("/fast")).getBytes(StandardCharsets.UTF_8);
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                sock.setSoTimeout(8000);
                sock.setTcpNoDelay(true);
                OutputStream out = sock.getOutputStream();
                out.write(pipelined);           // both requests, one write => one segment
                out.flush();

                InputStream in = sock.getInputStream();
                RawResponse first = readResponse(in);
                RawResponse second = readResponse(in);

                assertEquals(200, first.status,
                        "first pipelined response status (should be SLOW's)");
                assertEquals("{\"slow\":true}", first.body,
                        "first response body must be SLOW's — pipelined responses must be in REQUEST "
                                + "order; a fast body here means the fast response overtook/mis-framed");
                assertEquals(200, second.status,
                        "second pipelined response status (should be FAST's)");
                assertEquals("{\"ok\":true}", second.body,
                        "second response body must be FAST's");
            }
        }, "pipelined requests never both returned — per-connection FIFO stalled");
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

    /** A raw HTTP/1.1 keep-alive POST {@code {}} to {@code path} — for hand-pipelining onto a socket. */
    private String rawPost(String path) {
        String body = "{}";
        return "POST " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n"
                + body;
    }

    /** Status code + body of one HTTP response, decoupled from the socket. */
    private record RawResponse(int status, String body) {
    }

    /**
     * Reads exactly one HTTP/1.1 response off {@code in}: the status line, headers (to find
     * Content-Length), then that many body bytes. Only handles the shapes this test's endpoints
     * emit (fixed Content-Length, no chunking) — enough to assert ordering + framing.
     */
    private static RawResponse readResponse(InputStream in) throws Exception {
        String statusLine = readLine(in);
        if (statusLine == null) {
            throw new IllegalStateException("connection closed before a response was read");
        }
        // "HTTP/1.1 200 OK" -> the numeric code.
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        int contentLength = -1;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                contentLength = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        if (contentLength < 0) {
            throw new IllegalStateException("no Content-Length in response: " + statusLine);
        }
        byte[] body = in.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new IllegalStateException("truncated body: expected " + contentLength + " got " + body.length);
        }
        return new RawResponse(status, new String(body, StandardCharsets.UTF_8));
    }

    /** Reads one CRLF-terminated line (bytes, ASCII) from {@code in}; null at end of stream. */
    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
        }
        if (!any && buf.size() == 0) {
            return null;
        }
        return buf.toString(StandardCharsets.UTF_8);
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
