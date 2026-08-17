package tech.krpc.server.agent;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import io.grpc.Status;

/**
 * AGENT-ERRCODE-SEC: what an agent-facing HTTP client is allowed to read in an error {@code message}.
 *
 * <h2>Why this is a separate layer, and not part of the mapping</h2>
 * {@code UnaryMethod.toClientError} classifies — one table, one {@code code} per cause, identical on
 * every face. This class decides how much of the accompanying <em>description</em> that face may
 * show. <b>The code is uniform across faces; only the description's detail is graded by exposure.</b>
 * gRPC is a service-to-service surface where a detailed description earns its diagnostic value;
 * {@code /agent/invoke} and {@code /mcp} answer arbitrary agent callers.
 *
 * <h2>The rule: WHOSE FAULT is it, and does saying so give anything away</h2>
 * An earlier version of this class claimed to distinguish "text a service author wrote for the
 * caller" from "text nobody wrote for the caller". It could not: a description is a bare string
 * with no provenance, no marker type and no metadata, so that framing described an intent the code
 * had no way to implement. What it actually did was check the status code — which is fine, as long
 * as the rule is stated in terms of the status code and nothing else.
 *
 * <p>Descriptions pass through for exactly one class of status: <b>the caller's own request is at
 * fault, and naming the fault reveals nothing about us</b>. Those are the codes a service uses to
 * model a rejected request — {@code INVALID_ARGUMENT}, {@code NOT_FOUND}, {@code ALREADY_EXISTS},
 * {@code FAILED_PRECONDITION}, {@code OUT_OF_RANGE}. Everything else is replaced, including:
 *
 * <ul>
 *   <li><b>Our fault</b> — {@code INTERNAL}, {@code UNKNOWN}, {@code DATA_LOSS}, {@code ABORTED},
 *       {@code DEADLINE_EXCEEDED}. These descriptions carry the thrown class and its raw message:
 *       SQL fragments, connection strings, hostnames, file paths, and whatever an application
 *       exception interpolated — including the caller's own input echoed back.</li>
 *   <li><b>The caller's fault, but sensitive</b> — {@code UNAUTHENTICATED},
 *       {@code PERMISSION_DENIED}, {@code UNAVAILABLE}. The auth engine distinguishes "JWKS not
 *       ready" from "empty token" from "unknown kid" from "bad signature" from "expired", several
 *       quoting the {@code kid}, {@code exp} or client id back. To an unauthenticated caller that
 *       is a credential-state oracle: it turns "is my token rejected?" into "which part of my
 *       forgery was wrong?".</li>
 * </ul>
 *
 * <p><b>The default is to replace.</b> A code that is not in the pass-through set — including any
 * code a future gRPC version adds — is generic. Disclosure has to be opted into explicitly; that
 * is the same default-deny posture as the rest of this wave, and it is what makes the leak this
 * class was written to stop unrepeatable.
 *
 * <p>Nothing is lost operationally: the withheld detail is logged server-side (see
 * {@code UnaryMethod.logDispatchFailure}) against the same traceparent the client is handed for a
 * failure that is ours.
 */
final class AgentErrorMessage {

    private AgentErrorMessage() {
    }

    static final String INTERNAL = "internal error";

    /**
     * The ONLY statuses whose description reaches an agent caller: the request itself is wrong, and
     * saying how discloses nothing about the server. These are also precisely the codes a service
     * author uses to model a rejected request, so this is where the business error model lives
     * (AGENT-002 F2/F3) — {@code NOT_FOUND "city 999 does not exist"} still reaches the caller.
     */
    private static final Set<Status.Code> DESCRIPTION_IS_SAFE = EnumSet.of(
            Status.Code.INVALID_ARGUMENT,
            Status.Code.NOT_FOUND,
            Status.Code.ALREADY_EXISTS,
            Status.Code.FAILED_PRECONDITION,
            Status.Code.OUT_OF_RANGE);

    /**
     * The client-visible message for a dispatch failure.
     *
     * @param status      the classified status from {@code UnaryMethod.toClientError}
     * @param traceparent the inbound W3C traceparent, or null/blank when the caller sent none;
     *                    echoed only when the failure is ours, as the id to quote to support
     */
    static String forClient(Status status, String traceparent) {
        var code = status.getCode();
        if (DESCRIPTION_IS_SAFE.contains(code)) {
            var description = status.getDescription();
            return (null != description && !description.isBlank()) ? description : generic(code);
        }
        if (isOurFault(code)) {
            return (null == traceparent || traceparent.isBlank())
                    ? INTERNAL
                    : INTERNAL + ", trace=" + traceparent;
        }
        return generic(code);
    }

    /**
     * Whether the caller should read this as "the server broke" rather than "your request was
     * refused". Default-deny: anything not recognised as a caller-side refusal counts as ours, so
     * an unmapped or newly-added code gets the opaque answer rather than an accidental disclosure.
     */
    private static boolean isOurFault(Status.Code code) {
        return !DESCRIPTION_IS_SAFE.contains(code) && !isCallerRefusal(code);
    }

    /**
     * Caller-side refusals whose reason is nonetheless withheld. Kept distinct from "our fault" so
     * the client is not told the server broke when it did not — the code (16/7/14) already tells a
     * caller whether to re-authenticate, stop, or retry, which is all it needs.
     */
    private static boolean isCallerRefusal(Status.Code code) {
        return Status.Code.UNAUTHENTICATED == code
                || Status.Code.PERMISSION_DENIED == code
                || Status.Code.UNAVAILABLE == code
                || Status.Code.RESOURCE_EXHAUSTED == code
                || Status.Code.UNIMPLEMENTED == code
                || Status.Code.CANCELLED == code;
    }

    /** {@code PERMISSION_DENIED} -> {@code "permission denied"}: the code's name, nothing more. */
    private static String generic(Status.Code code) {
        return code.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
