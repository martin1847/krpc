package tech.krpc.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import tech.krpc.util.JsonUtils;

/**
 * HARDEN-B4 Part A — HTTP error-mapping contract (cases 1-7).
 *
 * <p>Drives {@link AbstractHttpHandler} through an in-process netty pipeline via
 * {@link EmbeddedChannel}. Each test defends an externally observable contract of the uniform JSON
 * error envelope {@code {"code":<status>,"message":<text>}} with {@code content-type:
 * application/json; charset=UTF-8}: status mapping, envelope shape, absence of leaked internal
 * strings, and (case 2) that a bad body yields a response instead of a bare connection reset.
 */
class HttpErrorMappingTest {

    // ---- case 1: malformed JSON POST -> 400, neutral, no Jackson/DTO leak -------------------

    @Test
    void malformedJsonPost_maps400NeutralEnvelope() {
        TestHandler h = new TestHandler(new FakeValidator(), new DtoEchoHandler());
        Resp r = post(h, "/echo", "{\"page\":");

        assertEquals(400, r.status());
        assertEquals(AbstractHttpHandler.TYPE_JSON, r.contentType());

        Map<String, Object> env = envelope(r.body());
        assertEquals(400, code(env));
        assertEquals("malformed JSON: cannot decode request body", env.get("message"));

        // No Jackson internals and no DTO field/class names reach the client.
        assertFalse(r.body().contains("com.fasterxml"), "leaked Jackson package");
        assertFalse(r.body().contains("line:"), "leaked parser line offset");
        assertFalse(r.body().contains("column:"), "leaked parser column offset");
        assertFalse(r.body().contains("page"), "leaked DTO field name");
        assertFalse(r.body().contains("PageDto"), "leaked DTO class name");
    }

    // ---- case 2 (AUD-omp-20): malformed body does NOT reset the connection ------------------

    @Test
    void malformedBody_producesResponseAndKeepsChannelOpen() {
        TestHandler h = new TestHandler(new FakeValidator(), new DtoEchoHandler());
        EmbeddedChannel ch = new EmbeddedChannel(h);
        try {
            ch.writeInbound(jsonPost("/echo", "{\"page\":"));

            Object out = ch.readOutbound();
            assertNotNull(out, "malformed body must emit a response, not a bare close/reset");
            assertTrue(out instanceof FullHttpResponse, "outbound must be an HTTP response");
            FullHttpResponse resp = (FullHttpResponse) out;
            assertEquals(400, resp.status().code());
            assertTrue(ch.isOpen(), "handler must not close the channel after a 400");
            resp.release();
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    // ---- case 3: empty body to a validating endpoint -> 400 "request body is required" ------

    @Test
    void emptyBodyToValidatingEndpoint_maps400BodyRequired() {
        TestHandler h = new TestHandler(new FakeValidator(), new ValidatingHandler());
        Resp r = post(h, "/validate", "");

        assertEquals(400, r.status());
        assertEquals(AbstractHttpHandler.TYPE_JSON, r.contentType());
        Map<String, Object> env = envelope(r.body());
        assertEquals(400, code(env));
        assertEquals("request body is required", env.get("message"));
    }

    @Test
    void blankBodyToValidatingEndpoint_maps400BodyRequired() {
        TestHandler h = new TestHandler(new FakeValidator(), new ValidatingHandler());
        Resp r = post(h, "/validate", "   ");

        assertEquals(400, r.status());
        assertEquals("request body is required", envelope(r.body()).get("message"));
    }

    // ---- case 4: validation failure -> 400, field path + message, NO rejected value leak ----

    @Test
    void validationFailure_maps400WithoutLeakingRejectedValue() {
        // A real jakarta.validation.Validator that reports one violation. The rejected value is a
        // distinctive sentinel; the contract says it must reach the log (detail) only, never the
        // client-facing envelope (safe = propertyPath + message).
        Validator failing = new FakeValidator(
                "page", "must be greater than or equal to 1", "REJECTED-SENTINEL-42");
        TestHandler h = new TestHandler(failing, new ValidatingHandler());

        Resp r = post(h, "/validate", "{\"page\":0}");

        assertEquals(400, r.status());
        assertEquals(AbstractHttpHandler.TYPE_JSON, r.contentType());

        Map<String, Object> env = envelope(r.body());
        assertEquals(400, code(env));
        String msg = (String) env.get("message");
        assertTrue(msg.contains("page"), "message must carry the field path");
        assertTrue(msg.contains("must be greater than or equal to 1"),
                "message must carry the constraint text");
        assertFalse(r.body().contains("REJECTED-SENTINEL-42"),
                "the rejected value must NOT leak to the client");
    }

    // ---- case 5 (C7): unknown path -> 404 JSON envelope with matching content-type ----------

    @Test
    void unknownPathPost_maps404JsonEnvelope() {
        TestHandler h = new TestHandler(new FakeValidator(), new DtoEchoHandler());
        Resp r = post(h, "/does-not-exist", "{}");

        assertEquals(404, r.status());
        assertEquals(AbstractHttpHandler.TYPE_JSON, r.contentType());
        assertEquals(404, code(envelope(r.body())));
    }

    @Test
    void unknownPathGet_maps404JsonEnvelope() {
        TestHandler h = new TestHandler(new FakeValidator(), new DtoEchoHandler());
        Resp r = get(h, "/does-not-exist");

        assertEquals(404, r.status());
        assertEquals(AbstractHttpHandler.TYPE_JSON, r.contentType());
        assertEquals(404, code(envelope(r.body())));
    }

    // ---- case 6: handler internal RuntimeException -> 500 neutral, no getMessage() leak ------

    @Test
    void handlerInternalError_maps500NeutralWithoutLeak() {
        TestHandler h = new TestHandler(new FakeValidator(), new ThrowingHandler());
        Resp r = post(h, "/boom", "anything");

        assertEquals(500, r.status());
        assertEquals(AbstractHttpHandler.TYPE_JSON, r.contentType());

        Map<String, Object> env = envelope(r.body());
        assertEquals(500, code(env));
        assertEquals("Internal Server Error", env.get("message"),
                "500 message must be the neutral status reason phrase");
        assertFalse(r.body().contains("SECRET-INTERNAL-abc"),
                "the internal exception message must NOT leak to the client");
    }

    // ---- case 7: oversize body -> 413 via the real HttpObjectAggregator(1048576) -------------

    @Test
    void oversizeBody_maps413ThroughRealPipeline() {
        TestHandler h = new TestHandler(new FakeValidator(), new DtoEchoHandler());
        // Real wiring the server uses: decoder -> aggregator(1 MiB) -> handler. A request whose
        // declared Content-Length exceeds the aggregator limit must be rejected with 413 by netty
        // before the handler ever sees it. Guards against a pipeline-wiring regression.
        EmbeddedChannel ch = new EmbeddedChannel(
                new HttpRequestDecoder(),
                new HttpObjectAggregator(1048576),
                h);
        try {
            int oversize = 1048576 + 1;
            String head = "POST /echo HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + oversize + "\r\n\r\n";
            ch.writeInbound(Unpooled.wrappedBuffer(head.getBytes(StandardCharsets.US_ASCII)));

            Object out = ch.readOutbound();
            assertNotNull(out, "aggregator must emit a 413 for an oversize declared body");
            assertTrue(out instanceof FullHttpResponse, "outbound must be an HTTP response");
            FullHttpResponse resp = (FullHttpResponse) out;
            assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.code(), resp.status().code());
            assertEquals(413, resp.status().code());
            resp.release();
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    // =====================================================================================
    // Harness
    // =====================================================================================

    /** Extracted response facts, decoupled from the (released) netty buffer. */
    private record Resp(int status, String contentType, String body, boolean channelOpen) {
    }

    private static Resp post(AbstractHttpHandler h, String uri, String body) {
        return drive(h, HttpMethod.POST, uri, body.getBytes(StandardCharsets.UTF_8));
    }

    private static Resp get(AbstractHttpHandler h, String uri) {
        return drive(h, HttpMethod.GET, uri, null);
    }

    private static Resp drive(AbstractHttpHandler h, HttpMethod method, String uri, byte[] body) {
        EmbeddedChannel ch = new EmbeddedChannel(h);
        try {
            FullHttpRequest req = (body == null)
                    ? new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri)
                    : new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri,
                            Unpooled.wrappedBuffer(body));
            if (body != null) {
                req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            }
            ch.writeInbound(req);

            FullHttpResponse resp = ch.readOutbound();
            assertNotNull(resp, "handler produced no response for " + method + " " + uri);
            Resp r = new Resp(
                    resp.status().code(),
                    resp.headers().get(HttpHeaderNames.CONTENT_TYPE),
                    resp.content().toString(StandardCharsets.UTF_8),
                    ch.isOpen());
            resp.release();
            return r;
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    private static FullHttpRequest jsonPost(String uri, String body) {
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri,
                Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8)));
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        return req;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envelope(String body) {
        return JsonUtils.parse(body, Map.class);
    }

    private static int code(Map<String, Object> env) {
        return ((Number) env.get("code")).intValue();
    }

    // ---- Test handler + registration ------------------------------------------------------

    /** Package-private concrete handler: registers POST handlers and supplies a Validator. */
    static final class TestHandler extends AbstractHttpHandler {
        private final Validator validator;

        TestHandler(Validator validator, PostHandler<?>... handlers) {
            this.validator = validator;
            for (PostHandler<?> handler : handlers) {
                postMap.put(handler.path(), handler);
            }
        }

        @Override
        public Validator getValidator() {
            return validator;
        }

        @Override
        public void initHandler() {
            // handlers are registered via the constructor
        }
    }

    public static final class PageDto {
        public int page;
    }

    /** Typed DTO handler with validation OFF: exercises the JSON-parse path (cases 1, 2, 5). */
    static final class DtoEchoHandler implements PostHandler<PageDto> {
        @Override
        public Class<PageDto> getParamClass() {
            return PageDto.class;
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
        public byte[] handle(PageDto param, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
            return "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String contextType() {
            return AbstractHttpHandler.TYPE_JSON;
        }
    }

    /** Typed DTO handler with validation ON: exercises empty-body + validation paths (3, 4). */
    static final class ValidatingHandler implements PostHandler<PageDto> {
        @Override
        public Class<PageDto> getParamClass() {
            return PageDto.class;
        }

        @Override
        public String path() {
            return "/validate";
        }

        @Override
        public boolean useValidator() {
            return true;
        }

        @Override
        public byte[] handle(PageDto param, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
            return Handler.EMPTY;
        }

        @Override
        public String contextType() {
            return AbstractHttpHandler.TYPE_JSON;
        }
    }

    /** String handler that throws inside handle(): exercises the 500 neutral path (case 6). */
    static final class ThrowingHandler implements PostHandler<String> {
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
            throw new RuntimeException("SECRET-INTERNAL-abc");
        }

        @Override
        public String contextType() {
            return AbstractHttpHandler.TYPE_JSON;
        }
    }

    // ---- Hand-written fakes for the real jakarta.validation contracts ---------------------
    // No bean-validation PROVIDER is on the test classpath (only jakarta.validation-api), so
    // Validation.buildDefaultValidatorFactory() would throw NoProviderFoundException. These fakes
    // implement the real interfaces and drive the exact production branch
    // (getValidator().validate(input) -> non-empty violation set -> safe/detail split -> 400),
    // while letting the rejected value be a distinctive sentinel so the no-leak assertion has teeth.

    static final class FakeValidator implements Validator {
        private final String path;
        private final String message;
        private final Object invalidValue;

        FakeValidator() {
            this(null, null, null);
        }

        FakeValidator(String path, String message, Object invalidValue) {
            this.path = path;
            this.message = message;
            this.invalidValue = invalidValue;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
            if (path == null) {
                return Set.of();
            }
            return Set.of((ConstraintViolation<T>) new FakeViolation(path, message, invalidValue));
        }

        @Override
        public <T> Set<ConstraintViolation<T>> validateProperty(
                T object, String propertyName, Class<?>... groups) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Set<ConstraintViolation<T>> validateValue(
                Class<T> beanType, String propertyName, Object value, Class<?>... groups) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.validation.metadata.BeanDescriptor getConstraintsForClass(Class<?> clazz) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.validation.executable.ExecutableValidator forExecutables() {
            throw new UnsupportedOperationException();
        }
    }

    static final class FakeViolation implements ConstraintViolation<Object> {
        private final Path propertyPath;
        private final String message;
        private final Object invalidValue;

        FakeViolation(String path, String message, Object invalidValue) {
            this.propertyPath = new FakePath(path);
            this.message = message;
            this.invalidValue = invalidValue;
        }

        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public Path getPropertyPath() {
            return propertyPath;
        }

        @Override
        public Object getInvalidValue() {
            return invalidValue;
        }

        @Override
        public String getMessageTemplate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getRootBean() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Class<Object> getRootBeanClass() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getLeafBean() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object[] getExecutableParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getExecutableReturnValue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.validation.metadata.ConstraintDescriptor<?> getConstraintDescriptor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U> U unwrap(Class<U> type) {
            throw new UnsupportedOperationException();
        }
    }

    static final class FakePath implements Path {
        private final String value;

        FakePath(String value) {
            this.value = value;
        }

        @Override
        public Iterator<Node> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
