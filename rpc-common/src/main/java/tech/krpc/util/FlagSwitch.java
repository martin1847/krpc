package tech.krpc.util;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-0003 (umbrella) requirements 3, 4 and 5: the memo cell behind a hand-written flag accessor,
 * plus the single resolution log line that is bound to it atomically.
 *
 * <p><b>Why the accessor keeps the read (requirement 3).</b> This class deliberately does NOT take a
 * resolver supplier or any other indirection for the read: a {@code Supplier}, method reference or
 * lambda hides the {@code System} call behind virtual dispatch or a lambda body, both of which the
 * ADR-0005 flag gate's layer 2 cannot follow — its "flag accessor" set is derived from the static
 * call graph, so the indirection would silently stop deriving {@code KrpcOtel.enabled()} and layer 2
 * would match nothing (the anti-vacuous-green guard covers layer 1 only). So the accessor reads on
 * the first-use path and hands the outcome here:
 *
 * <pre>{@code
 * public static boolean enabled() {
 *     Boolean memo = ENABLED.resolved();
 *     return memo != null ? memo : ENABLED.publish(read(PROPERTY, ENV));
 * }
 * }</pre>
 *
 * <p><b>One line, and the value is not observable before it exists (requirement 5).</b> Resolution
 * is one guarded step: the winner emits the line and only then publishes the value, all inside the
 * monitor. Two consequences, both load-bearing. Racing callers produce exactly one line describing
 * exactly the published value — never a loser's, and never a second line from a separate "have I
 * logged yet?" flag. And no caller can observe an effective value whose promised evidence has not
 * been written yet: a later caller either blocks on the monitor or, once past it, reads a value that
 * is already logged. Publishing first and logging after would leave that window open, and worse: if
 * the logging backend then threw, the value would stay published with its evidence permanently
 * missing — a state ADR-0003 requirement 5 explicitly calls worse than no log.
 *
 * <p><b>A failed emission does not publish.</b> If the backend throws, the caller still gets its
 * value (requirement 2: reading a flag must never break its caller) but nothing is memoised, so the
 * next caller re-emits and publishes. That trades a possible re-read of the environment on a broken
 * logging stack for never holding a value whose evidence cannot be produced.
 *
 * <p>The monitor is taken at most until the first successful publish; afterwards every call is a
 * single volatile read on the fast path (JDK 21+ virtual threads are not pinned by it on JDK 24+,
 * and this is one-shot work regardless).
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

    /**
     * Null until the first resolution is published — and it is published only AFTER its log line has
     * been emitted, so a non-null read always implies the evidence exists. This is the memo that
     * requirement 4 keeps off the call sites.
     */
    private volatile Boolean state;

    public FlagSwitch(String name, boolean defaultValue) {
        this.name = name;
        this.defaultValue = defaultValue;
    }

    /** The memoised value, or {@code null} while this flag has not been resolved yet. */
    public Boolean resolved() {
        return state;
    }

    /**
     * Resolves this flag in one guarded step if it is not resolved yet: emit the requirement-5 line,
     * then publish the value. Returns the value now in effect — the winner's, so racing callers
     * agree. When the emission fails, the caller's own value is returned and nothing is published,
     * leaving the line for the next caller to write (see class doc).
     */
    public boolean publish(FlagResolution resolution) {
        Boolean published = state;
        if (published != null) {
            return published;
        }
        synchronized (this) {
            published = state;
            if (published != null) {
                return published;
            }
            try {
                emit(resolution);
            } catch (RuntimeException | LinkageError failure) {
                // A broken logging backend must not break a flag read (requirement 2) and must not
                // leave a published value with no evidence: publish nothing, so the next caller
                // retries the line. Errors other than LinkageError (OOM, StackOverflow) are not this
                // class's to absorb.
                return resolution.value();
            }
            state = resolution.value();
            return resolution.value();
        }
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
