package tech.krpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

import tech.krpc.server.invoke.ValidationException;
import tech.krpc.util.JsonDecodeException;

/**
 * AGENT-ERRCODE: the exception -> {@link Status} table, which is now shared by BOTH dispatch paths
 * ({@code UnaryMethod.invoke} for gRPC, {@code UnaryMethod.invokeWeb} for {@code /agent/invoke} and
 * MCP). One table means one answer per input, whatever face the caller arrived on.
 *
 * <p>What went wrong before: only the gRPC path applied it. {@code invokeWeb} let the raw exception
 * escape, so {@code AgentInvokeHandler} reported a hardcoded {@code INTERNAL(13)} and MCP fell to
 * {@code Status.fromThrowable}'s {@code UNKNOWN(2)} — a malformed body and a failed field
 * validation both surfaced as "the server broke".
 *
 * <p>The truncation constant and the {@code traceId} prefix are part of the wire-visible
 * description, so they are pinned here too.
 */
class UnaryMethodErrorMappingTest {

    private static final String TRACE = ":trace-1";

    private static Status statusOf(Throwable mapped) {
        assertTrue(mapped instanceof StatusException || mapped instanceof StatusRuntimeException,
                () -> "mapping must always yield a Status carrier, got " + mapped.getClass());
        return Status.fromThrowable(mapped);
    }

    /** A malformed body is the CLIENT's fault: INVALID_ARGUMENT (3), never UNKNOWN or INTERNAL. */
    @Test
    void jsonDecodeException_becomesInvalidArgument() {
        var mapped = UnaryMethod.toClientError(
                new JsonDecodeException("malformed JSON: cannot decode request body",
                        new RuntimeException("Jackson internals")), TRACE);

        var status = statusOf(mapped);
        assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
        assertEquals(3, status.getCode().value(), "INVALID_ARGUMENT is gRPC 3");
        assertEquals(TRACE + ",malformed JSON request body", status.getDescription());
    }

    /** The sanitized description must not carry Jackson internals out to a client. */
    @Test
    void jsonDecodeException_descriptionLeaksNoJacksonDetail() {
        var jackson = new RuntimeException("Cannot coerce Integer 12345 into String, field 'phone'");
        var mapped = UnaryMethod.toClientError(new JsonDecodeException("x", jackson), TRACE);

        var description = statusOf(mapped).getDescription();
        assertTrue(description.contains("malformed JSON request body"), description);
        assertTrue(!description.contains("phone") && !description.contains("coerce"),
                () -> "sanitized description must not echo Jackson detail: " + description);
        // The chain is kept intact for server-side logs: Status.cause == the JsonDecodeException,
        // whose own cause is the raw Jackson failure. Nothing of it reaches the description.
        assertSame(jackson, statusOf(mapped).getCause().getCause(),
                "the raw Jackson cause stays reachable for server logs");
    }

    /**
     * {@link ValidationException} is already an {@code INVALID_ARGUMENT}
     * {@code StatusRuntimeException}, so it rides the pass-through branch untouched — field detail
     * intact. This is the input LH observed as {@code 13} on {@code /agent/invoke}.
     */
    @Test
    void validationException_passesThroughAsInvalidArgumentWithFieldDetail() {
        var original = new ValidationException("HelloRequest",
                List.of(new ValidationException.Violation("name", "must not be blank")));

        var mapped = UnaryMethod.toClientError(original, TRACE);

        assertSame(original, mapped, "an existing Status carrier must not be re-wrapped");
        var status = statusOf(mapped);
        assertEquals(3, status.getCode().value());
        assertTrue(status.getDescription().contains("name(must not be blank)"), status.getDescription());
    }

    /** Auth failures already carry their own Status and must survive unchanged. */
    @Test
    void existingStatusCarriers_passThroughUnchanged() {
        var denied = Status.PERMISSION_DENIED.withDescription("kid not found or expired")
                .asRuntimeException();
        assertSame(denied, UnaryMethod.toClientError(denied, TRACE));
        assertEquals(7, statusOf(UnaryMethod.toClientError(denied, TRACE)).getCode().value());

        var unauth = new StatusException(Status.UNAUTHENTICATED.withDescription("empty token"));
        assertSame(unauth, UnaryMethod.toClientError(unauth, TRACE));
        assertEquals(16, statusOf(UnaryMethod.toClientError(unauth, TRACE)).getCode().value());
    }

    /**
     * A genuinely unexpected server-side exception is UNKNOWN (2). Note it is NOT
     * {@code INTERNAL}(13): the gRPC face has always answered UNKNOWN here, and the HTTP faces now
     * agree with it instead of inventing their own code.
     */
    @Test
    void unexpectedException_becomesUnknown_notInternal() {
        var mapped = UnaryMethod.toClientError(new IllegalStateException("db pool exhausted"), TRACE);

        var status = statusOf(mapped);
        assertEquals(Status.Code.UNKNOWN, status.getCode());
        assertEquals(2, status.getCode().value());
        assertTrue(status.getDescription().startsWith(TRACE + ",IllegalStateException,"),
                status.getDescription());
    }

    /** Reflection wrappers are unwrapped first, so the CAUSE decides the code, not the wrapper. */
    @Test
    void invocationTargetException_isUnwrappedBeforeClassifying() {
        var decodeInside = new InvocationTargetException(
                new JsonDecodeException("malformed", new RuntimeException()));
        assertEquals(3, statusOf(UnaryMethod.toClientError(decodeInside, TRACE)).getCode().value(),
                "an unwrapped JsonDecodeException is still a client error");

        var businessInside = new InvocationTargetException(new IllegalArgumentException("boom"));
        var status = statusOf(UnaryMethod.toClientError(businessInside, TRACE));
        assertEquals(2, status.getCode().value());
        assertTrue(status.getDescription().contains("IllegalArgumentException"),
                () -> "the wrapper class must not be what the client sees: " + status.getDescription());
    }

    // ------------------------------------------------------------------------------------------
    // The correlation prefix. ServerContext.logTrace() returns ":" + traceparent, or "" when the
    // caller sent none -- it used to return the literal ":null". That only ever reached logs and
    // gRPC descriptions until the agent faces began surfacing the description to clients verbatim,
    // at which point a missing trace header would have shipped ":null," into a public API response.
    // ------------------------------------------------------------------------------------------

    /** With a traceparent: prefix present, comma-joined, unchanged from before. */
    @Test
    void withTraceparent_descriptionKeepsThePrefix() {
        var traced = ":00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        assertEquals(traced + ",malformed JSON request body",
                Status.fromThrowable(UnaryMethod.toClientError(
                        new JsonDecodeException("x", new RuntimeException()), traced))
                        .getDescription());

        assertTrue(Status.fromThrowable(UnaryMethod.toClientError(
                new IllegalStateException("boom"), traced))
                .getDescription().startsWith(traced + ",IllegalStateException,boom"));
    }

    /** Without one: NO prefix at all — not ":null", not ":", and not a bare leading comma. */
    @Test
    void withoutTraceparent_descriptionHasNoPrefixAtAll() {
        var decode = Status.fromThrowable(UnaryMethod.toClientError(
                new JsonDecodeException("x", new RuntimeException()), "")).getDescription();
        assertEquals("malformed JSON request body", decode);

        var unknown = Status.fromThrowable(UnaryMethod.toClientError(
                new IllegalStateException("boom"), "")).getDescription();
        assertEquals("IllegalStateException,boom", unknown);

        for (var description : List.of(decode, unknown)) {
            assertFalse(description.startsWith(","), () -> "no bare leading comma: " + description);
            assertFalse(description.contains("null"), () -> "no :null artefact: " + description);
            assertFalse(description.startsWith(":"), () -> "no empty prefix: " + description);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The logging axis. Withholding detail from the client is only defensible while the server
    // still records it -- an earlier version stacked ONLY UnknOWN, so a genuine INTERNAL (whose
    // description the client is NOT shown) left one bare line and no cause chain anywhere.
    // Asserted against a real logback appender, not by reading the code.
    // ------------------------------------------------------------------------------------------

    private static java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> captureLogs(
            Throwable mapped, Throwable original) {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(UnaryMethod.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            UnaryMethod.logDispatchFailure(TRACE, mapped, original);
            return java.util.List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** A genuine system fault keeps ERROR + the full throwable, so the cause chain survives. */
    @ParameterizedTest(name = "{0} is logged at ERROR with the full stack")
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"INTERNAL", "UNKNOWN", "DATA_LOSS", "ABORTED", "DEADLINE_EXCEEDED"})
    void serverFaults_keepTheFullStack(String code) {
        var original = new IllegalStateException("Table 'orders.payment' doesn't exist");
        var mapped = Status.fromCode(Status.Code.valueOf(code)).withCause(original)
                .asRuntimeException();

        var events = captureLogs(mapped, original);

        assertEquals(1, events.size(), () -> "expected exactly one log event: " + events);
        var event = events.get(0);
        assertEquals(ch.qos.logback.classic.Level.ERROR, event.getLevel(),
                () -> code + " must log at ERROR");
        assertNotNull(event.getThrowableProxy(),
                () -> code + " must carry the throwable -- the client is shown nothing, so this "
                        + "log is the ONLY record of the cause");
        assertEquals("Table 'orders.payment' doesn't exist",
                event.getThrowableProxy().getMessage(), "the real cause is recoverable");
    }

    /** A refused request gets one bounded line: no stack, and no echo of the rejected input. */
    @ParameterizedTest(name = "{0} is logged as a bounded WARN with no stack")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "INVALID_ARGUMENT", "NOT_FOUND", "ALREADY_EXISTS", "FAILED_PRECONDITION",
            "OUT_OF_RANGE", "UNAUTHENTICATED", "PERMISSION_DENIED", "UNAVAILABLE",
            "RESOURCE_EXHAUSTED", "UNIMPLEMENTED", "CANCELLED"})
    void refusedRequests_areBoundedAndStackless(String code) {
        var original = new IllegalArgumentException("rejected value 4111-1111-1111-1111");
        var mapped = Status.fromCode(Status.Code.valueOf(code)).asRuntimeException();

        var events = captureLogs(mapped, original);

        assertEquals(1, events.size());
        var event = events.get(0);
        assertEquals(ch.qos.logback.classic.Level.WARN, event.getLevel(),
                () -> code + " is a refusal, not a fault -- ERROR here trains operators to ignore it");
        assertNull(event.getThrowableProxy(),
                () -> code + " must not carry a stack: it is caller-triggerable at will, and a "
                        + "decode stack quotes the rejected input");
        assertFalse(event.getFormattedMessage().contains("4111-1111-1111-1111"),
                () -> "the rejected value must not reach the log: " + event.getFormattedMessage());
    }

    /** An over-long application message is truncated before it reaches the wire. */
    @Test
    void longMessage_isTruncated() {
        var mapped = UnaryMethod.toClientError(
                new RuntimeException("x".repeat(UnaryMethod.MAX_ERROR_LENGTH + 50)), TRACE);

        var description = statusOf(mapped).getDescription();
        assertNotNull(description);
        assertTrue(description.endsWith("..."), description);
        assertTrue(description.length() < UnaryMethod.MAX_ERROR_LENGTH + 60, description);
    }
}
