package tech.krpc.util;


import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-0003 (umbrella) requirements 3, 4 and 5: the memo cell behind a hand-written flag accessor,
 * plus the single resolution log line bound to it.
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
 * <p><b>Lock-free, and the log can never contradict the value.</b> The mechanism is two CAS cells and
 * nothing else — no monitor, no lock, no spin-wait anywhere on the resolution path:
 *
 * <ul>
 *   <li>{@link #canonical} is claimed once by {@code compareAndExchange} and never replaced. The
 *       first caller to arrive decides the flag; every later or racing caller — including one whose
 *       own re-read of the environment disagrees — is answered from that claim. So the effective
 *       value is immutable from the moment it is observable.</li>
 *   <li>{@link #lineClaimed} is a one-shot claim on the resolution line. Exactly one caller emits;
 *       whoever loses the claim returns immediately instead of waiting for the logging backend.</li>
 * </ul>
 *
 * <p>Requirement 5 forbids a log line decoupled from the value it reports, because a decoupled line
 * can name a value that was later recomputed ("bound to the first effective resolution"). With an
 * immutable claim that disease has no host: the line is always formatted from {@link #canonical},
 * which is the value every caller already received, so a separate claim for the line cannot make the
 * two disagree — it can only decide WHO writes it and that it is written once.
 *
 * <p><b>A failed emission keeps the value and owes the line.</b> If the backend throws, the caller
 * still gets its value (requirement 2: reading a flag must never break its caller), the claim stands,
 * and the line claim is released so the next read of this flag re-emits — the SAME stored resolution,
 * with no second look at the environment. A permanently broken logging stack therefore means
 * permanently no line (and one throwing attempt per read); that is an accepted cost, not a silently
 * different value.
 *
 * <p>Steady state after the line is out is two volatile reads and a branch: no allocation, no CAS,
 * no lock — which is why the OTEL flag can be consulted per request.
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
     * The resolution in effect: null until the first caller claims it, immutable afterwards. This is
     * the memo requirement 4 keeps off the call sites, and the only thing the line is formatted from.
     */
    private final AtomicReference<FlagResolution> canonical = new AtomicReference<>();

    /**
     * One-shot claim on the requirement-5 line. Taken by the caller that emits, released again only
     * when the backend threw, so the line is owed rather than lost.
     */
    private final AtomicBoolean lineClaimed = new AtomicBoolean();

    public FlagSwitch(String name, boolean defaultValue) {
        this.name = name;
        this.defaultValue = defaultValue;
    }

    /**
     * The resolved value, or {@code null} while this flag has not been resolved yet. Also settles the
     * resolution line if a previous attempt was lost to a broken backend — that retry reuses the
     * stored resolution and never re-reads the environment.
     */
    public Boolean resolved() {
        FlagResolution claimed = canonical.get();
        if (claimed == null) {
            return null;
        }
        emitOnce(claimed);
        return claimed.value();
    }

    /**
     * Claims {@code resolution} as this flag's value if nothing is claimed yet, then makes sure the
     * one line is written. Returns the value now in effect — the winner's, so racing callers agree
     * and a later re-read can never displace a value already handed out.
     */
    public boolean publish(FlagResolution resolution) {
        FlagResolution winner = canonical.compareAndExchange(null, resolution);
        if (winner == null) {
            winner = resolution;
        }
        emitOnce(winner);
        return winner.value();
    }

    /**
     * Writes the resolution line at most once. The plain read short-circuits the steady state (and
     * keeps a re-entrant logging backend from recursing); the CAS decides the single writer.
     */
    private void emitOnce(FlagResolution resolution) {
        if (lineClaimed.get() || !lineClaimed.compareAndSet(false, true)) {
            return;
        }
        try {
            emit(resolution);
        } catch (RuntimeException | LinkageError failure) {
            // A broken logging backend must not break a flag read (requirement 2). The value stands;
            // release the claim so the next read of this flag re-emits THIS resolution. Errors other
            // than LinkageError (OOM, StackOverflow) are not this class's to absorb.
            lineClaimed.set(false);
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
