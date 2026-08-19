package tech.krpc.util;

/**
 * ADR-0003 (umbrella) requirements 1 and 2: the one place raw property/environment text becomes a
 * krpc boolean flag value, together with <em>where</em> that value came from — requirement 5's log
 * line has to name the source, not only the effective state.
 *
 * <p><b>Value semantics (requirement 1).</b> Trimmed on both sides, case-insensitive:
 * {@code true}/{@code 1} is on, {@code false}/{@code 0} is off. Blank or absent counts as NOT
 * configured, so the flag's own default stands. A system property wins over the environment
 * variable; a <em>blank</em> property is not configuration, so it does not shadow a configured env
 * var. Nothing else is a recognised value.
 *
 * <p><b>Safe side (requirement 2).</b> Unrecognised text is never guessed at — it resolves to
 * {@code safeValue}, the side whose failure mode is recoverable. For a class-A kill switch that is
 * "the switch stays pressable" (OFF), because a behaviour stuck ON in production has no remedy
 * short of a rebuild while a spuriously OFF one is visible and fixed by correcting the value; for a
 * class-B capability it is "not exposed". A read failure resolves the same way — see the
 * {@code readFailure} factory, which every hand-written read site funnels its {@code catch} into so
 * that reading a flag can never propagate out of an accessor.
 *
 * <p>Pure and side-effect free: this type never touches {@code System}. The read itself stays in
 * the flag's own accessor, on the first-use path (requirement 3), which is also what keeps the
 * ADR-0005 flag gate able to derive that accessor as a flag accessor (its layer 2).
 */
public record FlagResolution(boolean value, Source source, String detail) {

    /** Where {@link FlagResolution#value()} came from — the {@code source=} half of the req-5 log. */
    public enum Source {
        /** An explicitly configured system property. */
        PROPERTY,
        /** An explicitly configured environment variable (no property configured). */
        ENV,
        /** Nothing configured: the flag's default stands. */
        DEFAULT,
        /** Configured, but not with a recognised value — resolved to the safe side. */
        UNRECOGNIZED,
        /** The lookup itself failed — resolved to the safe side. */
        READ_FAILURE
    }

    /** Cap for the echoed raw value in a log line; a flag value is never legitimately long. */
    private static final int DETAIL_MAX = 32;

    /**
     * Resolves already-read property/env text per requirements 1 and 2.
     *
     * @param defaultValue the value that stands when neither source is configured
     * @param safeValue    the value an unrecognised input resolves to (requirement 2)
     * @param property     raw system-property text, or {@code null} when the flag has no property
     *                     form / it is not readable here
     * @param env          raw environment-variable text, or {@code null}
     */
    public static FlagResolution of(boolean defaultValue, boolean safeValue,
                                    String property, String env) {
        String configuredProperty = configured(property);
        if (configuredProperty != null) {
            return parse(configuredProperty, Source.PROPERTY, safeValue);
        }
        String configuredEnv = configured(env);
        if (configuredEnv != null) {
            return parse(configuredEnv, Source.ENV, safeValue);
        }
        return new FlagResolution(defaultValue, Source.DEFAULT, null);
    }

    /**
     * Requirement 2: a failed lookup resolves to the safe side instead of escaping. Called from the
     * {@code catch} of a flag accessor's read; the exception's simple name becomes the logged
     * source detail so an operator can tell a broken lookup from a deliberate OFF.
     */
    public static FlagResolution readFailure(boolean safeValue, Throwable cause) {
        return new FlagResolution(safeValue, Source.READ_FAILURE, cause.getClass().getSimpleName());
    }

    /**
     * The {@code source=} text of the requirement-5 log line: {@code property}, {@code env},
     * {@code default}, {@code unrecognized(<raw>)} or {@code read-failure(<exception>)}.
     */
    public String describe() {
        String label = switch (source) {
            case PROPERTY -> "property";
            case ENV -> "env";
            case DEFAULT -> "default";
            case UNRECOGNIZED -> "unrecognized";
            case READ_FAILURE -> "read-failure";
        };
        return detail == null ? label : label + "(" + detail + ")";
    }

    private static FlagResolution parse(String trimmed, Source source, boolean safeValue) {
        if ("true".equalsIgnoreCase(trimmed) || "1".equals(trimmed)) {
            return new FlagResolution(true, source, null);
        }
        if ("false".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) {
            return new FlagResolution(false, source, null);
        }
        return new FlagResolution(safeValue, Source.UNRECOGNIZED, sanitise(trimmed));
    }

    /**
     * Requirement 1's trim, applied before anything else: {@code " true "} out of a YAML block
     * scalar, a Dockerfile line continuation or a terminal copy-paste is the same configuration as
     * {@code "true"}. Blank text is not configuration at all.
     */
    private static String configured(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Log hygiene: an unrecognised value is unvalidated input on its way into a log line. */
    private static String sanitise(String raw) {
        StringBuilder out = new StringBuilder(Math.min(raw.length(), DETAIL_MAX));
        for (int i = 0; i < raw.length() && i < DETAIL_MAX; i++) {
            char c = raw.charAt(i);
            out.append(Character.isISOControl(c) ? '?' : c);
        }
        if (raw.length() > DETAIL_MAX) {
            out.append("...");
        }
        return out.toString();
    }
}
