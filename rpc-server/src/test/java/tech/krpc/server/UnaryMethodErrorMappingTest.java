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

    // ------------------------------------------------------------------------------------------
    // A CAUSE raises the log floor. The status code alone is ambiguous -- RESOURCE_EXHAUSTED is a
    // quota refusal or a full disk, UNAVAILABLE is an unloaded JWKS or a dead dependency -- so the
    // discriminator is whether anything actually threw, which is a signal really present in the
    // data rather than another guess at intent.
    // ------------------------------------------------------------------------------------------

    /** Something threw: full stack, whatever the code says. */
    @ParameterizedTest(name = "{0} WITH a cause is logged at ERROR with the stack")
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"RESOURCE_EXHAUSTED", "UNAVAILABLE", "UNIMPLEMENTED", "NOT_FOUND",
                    "PERMISSION_DENIED", "CANCELLED"})
    void aCauseUpgradesEvenABoundedCode(String code) {
        var cause = new java.io.IOException("No space left on device: /var/lib/krpc/spool");
        var mapped = Status.fromCode(Status.Code.valueOf(code))
                .withDescription("could not accept the request").withCause(cause)
                .asRuntimeException();

        var event = captureLogs(mapped, cause).get(0);

        assertEquals(ch.qos.logback.classic.Level.ERROR, event.getLevel(),
                () -> code + " carrying a cause is a real failure, not a decision");
        assertNotNull(event.getThrowableProxy(), () -> code + " must keep the cause chain");
        assertEquals("No space left on device: /var/lib/krpc/spool",
                event.getThrowableProxy().getMessage(), "the real cause stays recoverable");
    }

    /**
     * A deliberately constructed refusal stays bounded — this is the property that keeps a flood
     * against a rate limiter from becoming a log-amplification lever.
     */
    @ParameterizedTest(name = "{0} with NO cause stays a bounded WARN")
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"RESOURCE_EXHAUSTED", "UNAVAILABLE", "UNIMPLEMENTED", "CANCELLED"})
    void aDeliberateRefusalWithoutACauseStaysBounded(String code) {
        var mapped = Status.fromCode(Status.Code.valueOf(code))
                .withDescription("daily quota reached").asRuntimeException();

        var event = captureLogs(mapped, new IllegalStateException("throttled")).get(0);

        assertEquals(ch.qos.logback.classic.Level.WARN, event.getLevel(),
                () -> code + " without a cause is a decision, not a fault");
        assertNull(event.getThrowableProxy(),
                () -> code + " must not hand an attacker a stack per request");
    }

    /**
     * THE INTERACTION THAT WOULD HAVE UNDONE THE PREVIOUS FIX. The real decode path attaches the
     * JsonDecodeException as the status cause, so a naive "cause upgrades" rule would put the
     * Jackson chain -- which quotes the rejected scalar and its surrounding input -- back into the
     * logs. INVALID_ARGUMENT therefore never upgrades.
     */
    @Test
    void realDecodeFailure_staysBounded_despiteCarryingACause() {
        var jackson = new RuntimeException(
                "Cannot coerce Integer 4111111111111111 into String, field 'card'");
        var original = new JsonDecodeException("malformed", jackson);
        var mapped = UnaryMethod.toClientError(original, TRACE);

        assertNotNull(Status.fromThrowable(mapped).getCause(),
                "precondition: the real mapping DOES attach a cause here");

        var event = captureLogs(mapped, original).get(0);

        assertEquals(ch.qos.logback.classic.Level.WARN, event.getLevel());
        assertNull(event.getThrowableProxy(), "the Jackson chain must not reach the log");
        assertFalse(event.getFormattedMessage().contains("4111111111111111"),
                () -> "the rejected value must not reach the log: " + event.getFormattedMessage());
        assertFalse(event.getFormattedMessage().contains("card"),
                () -> "not even the field name: " + event.getFormattedMessage());
    }

    /**
     * A bounded line still records the description, so a refusal is not invisible on BOTH sides —
     * this is where "JWKS not reachable at …" and "daily quota exceeded" survive for an operator.
     * INVALID_ARGUMENT is excluded for the same caller-data reason as above.
     */
    @Test
    void boundedLine_carriesTheDescription_exceptForInvalidArgument() {
        var withheld = Status.UNAVAILABLE
                .withDescription("JWKS not reachable at https://idp.internal/jwks")
                .asRuntimeException();
        var logged = captureLogs(withheld, new IllegalStateException("x")).get(0).getFormattedMessage();
        assertTrue(logged.contains("JWKS not reachable at https://idp.internal/jwks"),
                () -> "the client is not shown this, so the server must be: " + logged);

        var callerData = Status.INVALID_ARGUMENT
                .withDescription("Card : number('4111111111111111' is not a valid PAN)")
                .asRuntimeException();
        var suppressed = captureLogs(callerData, new IllegalStateException("x")).get(0)
                .getFormattedMessage();
        assertFalse(suppressed.contains("4111111111111111"),
                () -> "a custom validator's interpolated value must not reach the log: " + suppressed);
    }

    // ------------------------------------------------------------------------------------------
    // The logged description is partly ATTACKER-CONTROLLED: JwsVerify interpolates the request's
    // kid, the rejected exp/nbf, and the client id. "Bounded" therefore has to mean sanitized and
    // capped, not merely stackless.
    // ------------------------------------------------------------------------------------------

    /** A newline in the kid must not become a second log line. */
    @Test
    void attackerNewlineInDescription_cannotForgeALogLine() {
        var forged = Status.PERMISSION_DENIED
                .withDescription("kid not found or expired : \n[FAKE] admin login ok\r\nWARN done")
                .asRuntimeException();

        var message = captureLogs(forged, new IllegalStateException("x")).get(0).getFormattedMessage();

        assertFalse(message.contains("\n"), () -> "no bare newline may survive: " + message);
        assertFalse(message.contains("\r"), () -> "no bare carriage return may survive: " + message);
        assertEquals(1, message.split("\n", -1).length, () -> "must stay ONE line: " + message);
        // The text is still there, just defanged — evidence of the attempt is not destroyed.
        assertTrue(message.contains("[FAKE] admin login ok"), message);
    }

    /** Tabs and other control characters go the same way. */
    @Test
    void controlCharacters_areStripped() {
        assertFalse(UnaryMethod.sanitizeForLog("a\tb\u0000c\u0007d").matches(".*[\\p{Cntrl}].*"),
                "no control character may survive sanitizing");
        assertEquals("a b c", UnaryMethod.sanitizeForLog("a\t\t\tb   c"),
                "runs of control/space collapse to a single space");
    }

    /** A padded description cannot be used to inflate log volume. */
    @Test
    void oversizedDescription_isTruncatedWithAMarker() {
        var padded = "kid not found or expired : " + "A".repeat(50_000);
        var oversized = Status.PERMISSION_DENIED.withDescription(padded).asRuntimeException();

        var message = captureLogs(oversized, new IllegalStateException("x")).get(0)
                .getFormattedMessage();

        assertTrue(message.length() < UnaryMethod.MAX_LOGGED_DESCRIPTION + 120,
                () -> "a 50k description must not reach the log, length was " + message.length());
        assertTrue(message.endsWith("..."), () -> "truncation must be marked: " + message);
    }

    /** Sanitizing must not damage the descriptions that made logging worth doing. */
    @Test
    void normalDescription_survivesIntact() {
        var real = Status.UNAVAILABLE
                .withDescription("JWKS not reachable at https://idp.internal/jwks")
                .asRuntimeException();

        var message = captureLogs(real, new IllegalStateException("x")).get(0).getFormattedMessage();

        assertTrue(message.contains("JWKS not reachable at https://idp.internal/jwks"),
                () -> "an operator still needs this verbatim: " + message);
        assertFalse(message.endsWith("..."), () -> "a short description is not truncated: " + message);
    }

    /** Exactly-at-the-cap input is not marked as truncated. */
    @Test
    void descriptionAtTheCap_isNotMarkedTruncated() {
        var exact = "B".repeat(UnaryMethod.MAX_LOGGED_DESCRIPTION);
        assertEquals(exact, UnaryMethod.sanitizeForLog(exact));
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
