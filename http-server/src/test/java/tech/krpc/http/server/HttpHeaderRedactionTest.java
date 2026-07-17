package tech.krpc.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import io.netty.handler.codec.http.HttpHeaders;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * OTEL-002 Fix 3 (field defect #3): the http-server netty face logs inbound requests at DEBUG. A
 * user JWT rides the {@code access-token} cookie, the {@code Authorization} bearer, an unlisted
 * custom header, or a query parameter — none may appear verbatim in any log line. Captures the
 * <b>fully rendered encoder output</b> (R1-7), not just the format string, and exercises the
 * adversarial bypasses from R1-1/R1-2/R1-3.
 */
class HttpHeaderRedactionTest {

    private static final String JWT =
            "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ1c2VyLTQyIiwiZXhwIjo5OTk5OTk5OTk5fQ.c2lnLWJ5dGVz";

    private int port;
    private HttpServer server;
    private HttpClient client;
    private Logger handlerLogger;
    private OutputStreamAppender<ILoggingEvent> appender;
    private ByteArrayOutputStream rendered;
    private Level priorLevel;

    @BeforeEach
    void setUp() throws Exception {
        handlerLogger = (Logger) LoggerFactory.getLogger(AbstractHttpHandler.class);
        LoggerContext lc = handlerLogger.getLoggerContext();
        priorLevel = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.DEBUG);

        // R1-7: capture the COMPLETE rendered line (level, logger, ALL MDC, message) so a leak via
        // the layout / MDC / throwable is caught, not only the format string.
        PatternLayoutEncoder enc = new PatternLayoutEncoder();
        enc.setContext(lc);
        enc.setPattern("%-5level %logger{0} mdc=[%mdc] %msg%n");
        enc.start();
        rendered = new ByteArrayOutputStream();
        appender = new OutputStreamAppender<>();
        appender.setContext(lc);
        appender.setEncoder(enc);
        appender.setOutputStream(rendered);
        appender.start();
        handlerLogger.addAppender(appender);

        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        server = new HttpServer(new EchoHandler(), port);
        server.start();
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (server != null) server.shutdown();
        if (handlerLogger != null && appender != null) {
            handlerLogger.detachAppender(appender);
            appender.stop();
            handlerLogger.setLevel(priorLevel);
        }
    }

    @Test
    void masksJwtCookieAndAuthorization() throws Exception {
        send("/echo", HttpRequest.newBuilder()
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + JWT)
                .header("Cookie", "access-token=" + JWT + "; theme=dark"));

        String line = awaitRenderedContaining("HTTP inbound");
        assertNoJwt(line);
        assertTrue(line.contains("authorization=<redacted>"),
                () -> "Authorization value must be masked: " + line);
        assertTrue(line.contains("access-token=<redacted>"),
                () -> "the access-token cookie value must be masked: " + line);
        assertTrue(line.contains("theme=<redacted>"),
                () -> "all cookie values masked (default-deny): " + line);
        // Allow-listed header preserved for debuggability.
        assertTrue(line.contains("content-type=application/json"),
                () -> "diagnostic-safe header kept: " + line);
    }

    @Test
    void queryStringCredentialIsNotLogged() throws Exception {
        // R1-1: a credential in the query string must never be logged; only the raw path is.
        send("/echo?access_token=" + JWT + "&code=" + JWT, HttpRequest.newBuilder()
                .header("Content-Type", "application/json"));

        String line = awaitRenderedContaining("HTTP inbound");
        assertNoJwt(line);
        assertFalse(line.contains("access_token"), () -> "query key/value must not be logged: " + line);
        assertTrue(line.contains("/echo"), () -> "the raw path is logged: " + line);
    }

    @Test
    void unlistedTokenHeaderIsMasked() throws Exception {
        // R1-2: a header not on the allow-list is masked, not dumped in cleartext.
        send("/echo", HttpRequest.newBuilder()
                .header("Content-Type", "application/json")
                .header("X-Token", JWT)
                .header("X-Goog-Api-Key", JWT));

        String line = awaitRenderedContaining("HTTP inbound");
        assertNoJwt(line);
        assertTrue(line.contains("x-token=<redacted>"), () -> "unlisted header masked: " + line);
        assertTrue(line.contains("x-goog-api-key=<redacted>"), () -> "unlisted header masked: " + line);
    }

    @Test
    void noRenderedLineContainsTheLiveToken() throws Exception {
        send("/echo?token=" + JWT, HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + JWT)
                .header("Cookie", "access-token=" + JWT)
                .header("Content-Type", "application/json"));
        awaitRenderedContaining("HTTP inbound");
        assertNoJwt(rendered.toString(StandardCharsets.UTF_8));
    }

    private void assertNoJwt(String text) {
        assertFalse(text.contains(JWT), () -> "a live JWT leaked into the rendered log: " + text);
    }

    private void send(String pathAndQuery, HttpRequest.Builder b) throws Exception {
        HttpResponse<String> r = client.send(
                b.uri(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), () -> "request must succeed: " + r.body());
    }

    private String awaitRenderedContaining(String needle) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String all = rendered.toString(StandardCharsets.UTF_8);
            for (String line : all.split("\n")) {
                if (line.contains(needle)) {
                    return line;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no rendered log line containing '" + needle + "'; captured="
                + rendered.toString(StandardCharsets.UTF_8));
    }

    static final class EchoHandler extends AbstractHttpHandler {
        EchoHandler() {
            postMap.put("/echo", new PostHandler<String>() {
                @Override
                public Class<String> getParamClass() {
                    return String.class;
                }

                @Override
                public String path() {
                    return "/echo";
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
            });
        }

        @Override
        public Validator getValidator() {
            return null;
        }

        @Override
        public void initHandler() {
        }
    }
}
