package tech.krpc.server.invoke;

import java.util.List;
import java.util.stream.Collectors;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * AGENT-002 (F1/F3): typed jakarta-validation failure — the single source of validation error
 * shape across both faces.
 *
 * <p><b>MCP face</b> reads the typed {@link #violations()} ({@code {field, constraint}} pairs) to
 * build a structured envelope, never parsing a display string.
 *
 * <p><b>CLASSIC gRPC face</b> only sees the {@code io.grpc.Status}, so the field-level detail is
 * also encoded into the status <em>description</em> — {@code "Dto : field(message)[; field2(...)]"}
 * — preserving that face's contract (INVALID_ARGUMENT is field-level self-correctable) for remote
 * clients that never see the JVM-local carrier.
 *
 * <p><b>Both</b> descriptions are built ONLY from {@code field} + the jakarta constraint message
 * ({@code ConstraintViolation.getMessage()}, a static developer/framework string). The rejected
 * value ({@code getInvalidValue()}) is NEVER read, so no user value reaches the description, the
 * wire, trailers, or logs.
 */
public final class ValidationException extends StatusRuntimeException {

    /** One field-level violation: the property path and its (non-user) constraint message. */
    public record Violation(String field, String constraint) {}

    private final transient List<Violation> violations;

    public ValidationException(String dtoName, List<Violation> violations) {
        super(Status.INVALID_ARGUMENT.withDescription(describe(dtoName, violations)));
        this.violations = List.copyOf(violations);
    }

    /** The field/constraint pairs — never contains a rejected value. */
    public List<Violation> violations() {
        return violations;
    }

    /**
     * The classic-face status description: {@code "Dto : field(message)[; field2(message2)]"}.
     * Field names + constraint messages only — no rejected values, ever.
     */
    static String describe(String dtoName, List<Violation> violations) {
        var joined = violations.stream()
                .map(v -> v.field() + "(" + v.constraint() + ")")
                .collect(Collectors.joining("; "));
        return (null != dtoName && !dtoName.isBlank()) ? dtoName + " : " + joined : joined;
    }
}
