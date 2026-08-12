package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * AGENT-ERRCODE-SEC: what an agent-facing client may read in an error message.
 *
 * <p>The regression this guards: when the agent faces started reporting the real gRPC status they
 * also started echoing its <em>description</em> verbatim. For an unexpected failure that
 * description is the thrown class plus its raw {@code getMessage()} — SQL, paths, interpolated
 * input — and for an auth failure it is the precise reason, which is a credential-state oracle.
 * Grading the detail by exposure is NOT a re-split of the error mapping: the {@code code} stays
 * identical on every face, only how much text accompanies it differs.
 */
class AgentErrorMessageTest {

    private static final String TRACE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    // ---------------------------------------------------------------- kept: the caller's own request

    /** Validation detail is about the CALLER's request, so it survives — that is the whole point. */
    @Test
    void validationDetail_isPreserved() {
        var status = Status.INVALID_ARGUMENT.withDescription("HelloRequest : name(must not be blank)");
        assertEquals("HelloRequest : name(must not be blank)",
                AgentErrorMessage.forClient(status, null));
    }

    /** The sanitized decode text likewise passes through unchanged. */
    @Test
    void decodeFailureText_isPreserved() {
        var status = Status.INVALID_ARGUMENT.withDescription(":" + TRACE + ",malformed JSON request body");
        assertEquals(":" + TRACE + ",malformed JSON request body",
                AgentErrorMessage.forClient(status, TRACE));
    }

    /**
     * Business statuses keep their message. The regression that caught this: an over-broad
     * "collapse everything that is not INVALID_ARGUMENT" rule silently deleted the authored error
     * model (AGENT-002 F2/F3) — a service returning {@code NOT_FOUND "city 999 does not exist"}
     * started answering a bare {@code "not found"}. Only UNAUTHORED text is withheld.
     */
    @ParameterizedTest(name = "{0} keeps its authored description")
    @CsvSource({
            "NOT_FOUND,          city 999 does not exist",
            "ALREADY_EXISTS,     order 42 already submitted",
            "FAILED_PRECONDITION, cart must not be empty",
            "RESOURCE_EXHAUSTED, daily quota reached",
    })
    void businessStatuses_keepTheirAuthoredMessage(String code, String authored) {
        var status = Status.fromCode(Status.Code.valueOf(code)).withDescription(authored);
        assertEquals(authored, AgentErrorMessage.forClient(status, TRACE));
    }

    // ---------------------------------------------------------------- withheld: server internals

    /**
     * An unexpected failure surrenders nothing. The description below is exactly the shape
     * {@code toClientError} builds for an unmapped exception, and every identifying fragment of it
     * must be absent from what the client reads.
     */
    @Test
    void unexpectedFailure_leaksNoClassNameMessageOrInternals() {
        var leaky = Status.UNKNOWN.withDescription(
                ":trace,SQLTransientConnectionException,HikariPool-1 - Connection is not available,"
                        + " request timed out after 30000ms (total=10, active=10) jdbc:mysql://"
                        + "db-prod-3.internal:3306/orders");

        var message = AgentErrorMessage.forClient(leaky, null);

        assertEquals(AgentErrorMessage.INTERNAL, message);
        for (var secret : new String[]{"SQLTransient", "HikariPool", "jdbc:mysql", "db-prod-3",
                "internal:3306", "orders", "30000ms", "active=10"}) {
            assertFalse(message.contains(secret),
                    () -> "server internals must not reach the client: " + secret + " in " + message);
        }
    }

    /** With an inbound trace the client gets a correlation id — and still nothing else. */
    @Test
    void unexpectedFailure_echoesOnlyTheTraceparent() {
        var leaky = Status.UNKNOWN.withDescription(":trace,IllegalStateException,secret detail");

        var message = AgentErrorMessage.forClient(leaky, TRACE);

        assertEquals(AgentErrorMessage.INTERNAL + ", trace=" + TRACE, message);
        assertFalse(message.contains("IllegalStateException"), message);
        assertFalse(message.contains("secret detail"), message);
    }

    /**
     * The credential-state oracle: every distinguishable auth outcome must collapse to ONE string
     * per code. If any of these came back different, an unauthenticated caller could probe which
     * part of a forged token was wrong.
     */
    @ParameterizedTest(name = "auth reason \"{1}\" collapses to \"{2}\"")
    @CsvSource({
            "UNAUTHENTICATED,   requireCredential but empty token,        unauthenticated",
            "UNAUTHENTICATED,   malformed JWT: expected 3 segments,       unauthenticated",
            "UNAUTHENTICATED,   Token expired at: 1730000000,             unauthenticated",
            "UNAUTHENTICATED,   token nbf is in the future: 1730000000,   unauthenticated",
            "PERMISSION_DENIED, kid not found or expired,                 permission denied",
            "PERMISSION_DENIED, invalid signature !,                      permission denied",
            "PERMISSION_DENIED, aud mismatch: expected api://orders,      permission denied",
            "PERMISSION_DENIED, client-id binding failed for cid-42,      permission denied",
            "UNAVAILABLE,       JWKS not reachable at https://idp/jwks,   unavailable",
    })
    void authReasons_collapseToOneStringPerCode(String code, String reason, String expected) {
        var status = Status.fromCode(Status.Code.valueOf(code)).withDescription(reason);
        assertEquals(expected, AgentErrorMessage.forClient(status, TRACE));
    }

    /** No auth message may carry a kid, an expiry, an audience or a client id. */
    @Test
    void authMessages_carryNoIdentifiers() {
        var status = Status.PERMISSION_DENIED.withDescription(
                "kid=abc123 exp=1730000000 aud=api://orders client-id=cid-42 signature invalid");

        var message = AgentErrorMessage.forClient(status, TRACE);

        assertEquals("permission denied", message);
        for (var identifier : new String[]{"abc123", "1730000000", "api://orders", "cid-42"}) {
            assertFalse(message.contains(identifier),
                    () -> identifier + " must not reach an unauthenticated caller: " + message);
        }
    }

    /** A missing description must not produce "null" text. */
    @Test
    void absentDescription_fallsBackToTheCodeName() {
        assertEquals("invalid argument",
                AgentErrorMessage.forClient(Status.INVALID_ARGUMENT, null));
        assertTrue(AgentErrorMessage.forClient(Status.NOT_FOUND, null).length() > 0);
        assertFalse(AgentErrorMessage.forClient(Status.INVALID_ARGUMENT, null).contains("null"));
    }
}
