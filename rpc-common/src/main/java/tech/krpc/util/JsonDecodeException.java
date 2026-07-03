package tech.krpc.util;

/**
 * AUD-omp-31: a typed, message-sanitized JSON decode failure.
 *
 * <p>Pre-fix, {@code JsonUtils.parse} rethrew Jackson's {@code JsonProcessingException} as a bare
 * {@code RuntimeException} whose message carried Jackson internals (field names, class names, source
 * offsets). On the server request path that escaped as gRPC {@code UNKNOWN}/500 and leaked those
 * internals to the client. This exception replaces that:
 *
 * <ul>
 *   <li>The <b>message is neutral</b> ("malformed JSON …") — safe to forward to a client.</li>
 *   <li>The original Jackson exception is retained ONLY as the {@code cause}, for server-side logs.</li>
 * </ul>
 *
 * <p>It stays a plain {@link RuntimeException} on purpose so the existing catch sites keep working
 * unchanged: the JWT verify path ({@code JwsVerify.verify}) already maps any {@code RuntimeException}
 * from a malformed token to {@code UNAUTHENTICATED}, and the client deserialization path still sees a
 * runtime failure. Only the server request path ({@code UnaryMethod}) special-cases it, mapping it to
 * {@code INVALID_ARGUMENT}.
 */
public class JsonDecodeException extends RuntimeException {

    public JsonDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
