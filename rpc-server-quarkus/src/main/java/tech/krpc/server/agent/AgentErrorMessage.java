package tech.krpc.server.agent;

import java.util.Locale;

import io.grpc.Status;

/**
 * AGENT-ERRCODE-SEC: what an agent-facing HTTP client is allowed to read in an error {@code message}.
 *
 * <h2>Why this is a separate layer, and not part of the mapping</h2>
 * {@code UnaryMethod.toClientError} classifies — one table, one {@code code} per cause, identical on
 * every face. This class decides how much of the accompanying <em>description</em> that face may
 * show. <b>Those are deliberately different concerns and this is not a re-split of the mapping:
 * the code is uniform across faces; the description's detail level is graded by exposure.</b> The
 * gRPC face is a service-to-service surface where a detailed description is worth its diagnostic
 * value; {@code /agent/invoke} and {@code /mcp} answer arbitrary agent callers, so they get the
 * code plus the least text that still lets a legitimate caller act.
 *
 * <h2>What it withholds, and why</h2>
 * <ul>
 *   <li><b>Unexpected failures</b> ({@code UNKNOWN}) carry the thrown class name and its raw
 *       {@code getMessage()} in the description. That is SQL fragments, connection strings, file
 *       paths, internal class names, and whatever an application exception happened to interpolate
 *       — including the caller's own rejected input. None of it crosses this boundary.</li>
 *   <li><b>Authentication and authorization outcomes</b> are collapsed to one string per code. The
 *       underlying descriptions distinguish "JWKS not ready" from "empty token" from "unknown kid"
 *       from "bad signature" from "expired", several of them quoting the {@code kid}, {@code exp}
 *       or client id back. To an unauthenticated caller that is a credential-state oracle: it turns
 *       "is this token rejected?" into "which part of my forgery was wrong?".</li>
 * </ul>
 *
 * <h2>What it keeps</h2>
 * Every AUTHORED description survives intact: {@code INVALID_ARGUMENT} describing the caller's own
 * request ({@code name(must not be blank)}, {@code malformed JSON request body}), and business
 * statuses such as {@code NOT_FOUND} / {@code ALREADY_EXISTS} / {@code FAILED_PRECONDITION} whose
 * message the service author wrote deliberately for the caller (AGENT-002 F2/F3). Withholding those
 * would not close a leak — it would delete the error model. Only UNAUTHORED text is withheld: an
 * exception's raw message, and the auth engine's internal reason.
 *
 * <p>The withheld detail is not lost: {@code UnaryMethod} logs it server-side with the same
 * traceparent the client is handed back for {@code UNKNOWN}, so an operator can join the two.
 */
final class AgentErrorMessage {

    private AgentErrorMessage() {
    }

    static final String INTERNAL = "internal error";

    /**
     * The client-visible message for a dispatch failure.
     *
     * @param status      the classified status from {@code UnaryMethod.toClientError}
     * @param traceparent the inbound W3C traceparent, or null/blank when the caller sent none;
     *                    echoed ONLY for {@code UNKNOWN}, as the correlation id to quote to support
     */
    static String forClient(Status status, String traceparent) {
        var code = status.getCode();
        if (Status.Code.UNKNOWN == code) {
            return (null == traceparent || traceparent.isBlank())
                    ? INTERNAL
                    : INTERNAL + ", trace=" + traceparent;
        }
        if (isCredentialOutcome(code)) {
            return generic(code);
        }
        // Everything else keeps its description. These are AUTHORED statuses -- a validation
        // failure describing the caller's own fields, or a business NOT_FOUND / ALREADY_EXISTS /
        // FAILED_PRECONDITION whose message application code wrote deliberately for the caller
        // (AGENT-002 F2/F3). Collapsing those would not close a leak, it would delete the error
        // model. Only unauthored text -- an exception's raw message, or an auth engine's internal
        // reason -- is withheld.
        var description = status.getDescription();
        return (null != description && !description.isBlank()) ? description : generic(code);
    }

    /**
     * The credential family, whose descriptions are written by the auth engine rather than by the
     * service author, and whose variety is exactly what makes them an oracle. {@code UNAVAILABLE}
     * is here because that is how a not-yet-ready JWKS surfaces: "the trust root is not loaded" is
     * infrastructure state an unauthenticated caller should not be able to probe.
     */
    private static boolean isCredentialOutcome(Status.Code code) {
        return Status.Code.UNAUTHENTICATED == code
                || Status.Code.PERMISSION_DENIED == code
                || Status.Code.UNAVAILABLE == code;
    }

    /** {@code PERMISSION_DENIED} -> {@code "permission denied"}: the code's name, nothing more. */
    private static String generic(Status.Code code) {
        return code.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
