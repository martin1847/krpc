package tech.krpc.util;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-0003 (umbrella) requirements 3, 4 and 5: the memo cell behind a hand-written flag accessor,
 * plus the single resolution log line that is bound to it atomically.
 *
 * <p><b>Why the accessor keeps the read (requirement 3).</b> This class deliberately does NOT take
 * a resolver supplier and does NOT memoise in a holder class. Both would move the environment read
 * out of the accessor's own body: a holder class puts it back into a {@code <clinit>} — which
 * GraalVM/Quarkus run at image BUILD time, welding the switch shut — and a supplier hides it behind
 * virtual dispatch, where the ADR-0005 flag gate's layer 2 (which derives "flag accessor" from the
 * static call graph) can no longer see it, silently weakening the gate that guards requirement 4.
 * So the accessor reads on the first-use path and hands the outcome here:
 *
 * <pre>{@code
 * public static boolean enabled() {
 *     Boolean memo = ENABLED.resolved();
 *     return memo != null ? memo : ENABLED.publish(read(PROPERTY, ENV));
 * }
 * }</pre>
 *
 * <p><b>One line, reporting the value that won (requirement 5).</b> The log is emitted inside the
 * {@code compareAndSet} that publishes the value, never by a separate "have I logged yet?" flag:
 * racing callers may each compute a resolution, but exactly one publishes, and the one line
 * describes exactly that published value. A log decoupled from the publish could report a state
 * that was recomputed differently — misleading evidence in the one place evidence was promised.
 *
 * <p>Level is INFO, or WARN when the resolution turns OFF a behaviour that defaults ON: a pressed
 * kill switch is an unusual state an operator should see without looking for it. Never DEBUG —
 * production ships with DEBUG off, and on a native image this line is the only available proof that
 * the flag was resolved at runtime rather than baked in at build time.
 */
public final class FlagSwitch {

    private static final Logger log = LoggerFactory.getLogger(FlagSwitch.class);

    /** Flag name as an operator configures it, e.g. {@code "rpc.otel.enabled / KRPC_OTEL"}. */
    private final String name;

    /** The value that stands when nothing is configured — decides the log level (see class doc). */
    private final boolean defaultValue;

    /** Null until the first resolution is published; the memo requirement 4 keeps off call sites. */
    private final AtomicReference<Boolean> state = new AtomicReference<>();

    public FlagSwitch(String name, boolean defaultValue) {
        this.name = name;
        this.defaultValue = defaultValue;
    }

    /** The memoised value, or {@code null} while this flag has not been resolved yet. */
    public Boolean resolved() {
        return state.get();
    }

    /**
     * Publishes {@code resolution} as this flag's value if it is the first one, logging it in the
     * same guarded step, and returns the value that is now in effect — the winner's, never the
     * caller's own, so two racing callers agree.
     */
    public boolean publish(FlagResolution resolution) {
        if (state.compareAndSet(null, resolution.value())) {
            emit(resolution);
            return resolution.value();
        }
        return state.get();
    }

    private void emit(FlagResolution resolution) {
        String message = "ADR-0003 flag {} resolved: enabled={} source={}";
        if (defaultValue && !resolution.value()) {
            log.warn(message, name, resolution.value(), resolution.describe());
        } else {
            log.info(message, name, resolution.value(), resolution.describe());
        }
    }
}
