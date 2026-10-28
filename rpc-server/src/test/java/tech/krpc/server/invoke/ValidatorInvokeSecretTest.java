package tech.krpc.server.invoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import io.grpc.Status;
import org.junit.jupiter.api.Test;

import tech.krpc.server.ServerContext;

/**
 * AGENT-002 F1 (CRITICAL): {@link ValidatorInvoke} must NEVER surface the rejected value.
 *
 * <p>The stub {@link ConstraintViolation#getInvalidValue()} returns a secret string; the test
 * proves it appears NOWHERE in the thrown {@link ValidationException} — not in the gRPC status
 * description (which carries field(constraint) for the classic face, value-free) and not in the
 * typed {@code violations()}. Only {field, constraint} survive.
 */
class ValidatorInvokeSecretTest {

    private static final String SECRET = "hunter2-TOP-SECRET-token";

    /** A ValidatorInvoke whose readInput returns the supplied bean and never touches ServerContext. */
    private static ValidatorInvoke<String> invokeWith(Validator validator, Object bean) {
        return new ValidatorInvoke<>(validator, in -> {
            throw new AssertionError("method body must not run when validation fails");
        }) {
            @Override
            public Object readInput(ServerContext sc) {
                return bean;
            }
        };
    }

    @Test
    void validationFailure_neverLeaksRejectedValue() {
        // A single violation on field "password" whose invalid value is a secret.
        Validator validator = ValidationTestStubs.validator(Set.of(
                ValidationTestStubs.violation("password", "must not be blank", SECRET)));

        var invoke = invokeWith(validator, "any-bean");

        var ex = assertThrows(ValidationException.class, () -> invoke.invoke(null));

        // gRPC status: INVALID_ARGUMENT; description carries field(constraint) for the classic
        // face's self-correction contract, but NO rejected value.
        Status status = Status.fromThrowable(ex);
        assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
        assertEquals("String : password(must not be blank)", status.getDescription(),
                "description = Dto : field(message), value-free");

        // Typed carrier: {field, constraint} only.
        List<ValidationException.Violation> violations = ex.violations();
        assertEquals(1, violations.size());
        assertEquals("password", violations.get(0).field());
        assertEquals("must not be blank", violations.get(0).constraint());

        // The secret must not appear ANYWHERE the exception can be serialized/logged from.
        String haystack = String.valueOf(status.getDescription()) + "|" + violations
                + "|" + ex.getMessage() + "|" + ex.getStatus();
        assertFalse(haystack.contains(SECRET),
                () -> "rejected secret value leaked: " + haystack);
    }

    @Test
    void multipleViolations_descriptionSemicolonJoined_noValues() {
        Validator validator = ValidationTestStubs.validator(Set.of(
                ValidationTestStubs.violation("token", "size must be >= 32", SECRET),
                ValidationTestStubs.violation("email", "must be a valid email", "leak@evil.example")));

        var ex = assertThrows(ValidationException.class,
                () -> invokeWith(validator, "bean").invoke(null));

        assertEquals(2, ex.violations().size());

        // Description is "String : field(message); field(message)" — field+constraint only.
        String desc = Status.fromThrowable(ex).getDescription();
        assertTrue(desc.contains("token(size must be >= 32)"), () -> "field-level detail: " + desc);
        assertTrue(desc.contains("email(must be a valid email)"), () -> "field-level detail: " + desc);
        assertTrue(desc.contains(";"), () -> "multiple violations are ';'-joined: " + desc);

        String haystack = desc + "|" + ex.violations();
        assertFalse(haystack.contains(SECRET) || haystack.contains("leak@evil.example"),
                () -> "no rejected value anywhere: " + haystack);
        assertTrue(ex.violations().stream().anyMatch(v -> "token".equals(v.field())));
        assertTrue(ex.violations().stream().anyMatch(v -> "email".equals(v.field())));
    }
}
