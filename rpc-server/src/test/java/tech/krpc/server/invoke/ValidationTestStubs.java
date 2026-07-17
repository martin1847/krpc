package tech.krpc.server.invoke;

import java.util.Iterator;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;

/**
 * Shared jakarta-validation test stubs (no hibernate-validator on the classpath). Only the two
 * accessors {@link ValidatorInvoke} touches — {@code getPropertyPath()} and {@code getMessage()}
 * — carry data; {@code getInvalidValue()} returns the (secret) rejected value so a test can prove
 * it is never read into the status description, the wire, or the typed carrier.
 */
public final class ValidationTestStubs {

    private ValidationTestStubs() {}

    public static Validator validator(Set<ConstraintViolation<Object>> violations) {
        return new StubValidator(violations);
    }

    public static ConstraintViolation<Object> violation(String field, String message, Object invalidValue) {
        return new StubViolation(field, message, invalidValue);
    }

    /** Stub Path: only toString() (what ValidatorInvoke reads) is meaningful. */
    private record StubPath(String name) implements Path {
        @Override
        public Iterator<Node> iterator() {
            return java.util.Collections.emptyIterator();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Stub ConstraintViolation: getInvalidValue returns the secret to prove it is never read. */
    private record StubViolation(String field, String message, Object invalidValue)
            implements ConstraintViolation<Object> {
        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public Path getPropertyPath() {
            return new StubPath(field);
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
        public ConstraintDescriptor<?> getConstraintDescriptor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U> U unwrap(Class<U> type) {
            throw new UnsupportedOperationException();
        }
    }

    /** Stub Validator: only validate(bean) returns the fixed violation set. */
    private record StubValidator(Set<ConstraintViolation<Object>> violations) implements Validator {
        @Override
        @SuppressWarnings("unchecked")
        public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
            return (Set<ConstraintViolation<T>>) (Set<?>) violations;
        }

        @Override
        public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, Class<?>... groups) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Set<ConstraintViolation<T>> validateValue(Class<T> beanType, String propertyName, Object value, Class<?>... groups) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
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
}
