package tech.krpc.client;

import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;

/**
 * O2 (HARDEN-B2): single client-side authority for the default call deadline.
 *
 * <p>Before this, an outbound call with no explicit deadline used {@link CallOptions#DEFAULT} —
 * i.e. no deadline at all — so a hung/half-open upstream blocked the calling virtual thread
 * forever. This holds one configurable default (millis) and applies it in the one place a call is
 * built, iff the caller has not already set a deadline. Framework autoconfig layers (Spring today,
 * a future Quarkus client) only bind config into {@link #setDefaultDeadlineMillis(long)} — the
 * application logic lives here, once, so the two never drift (mirrors the Batch-1
 * {@code JwsVerify.bootstrapAndRegister} dedup).
 *
 * <p><b>Default behaviour change:</b> the compiled-in default is {@value #DEFAULT_DEADLINE_MILLIS}
 * ms. {@code 0} or negative = unlimited = pre-HARDEN-B2 behaviour. Precedence, highest first:
 * an explicit deadline ({@code OPTION_LOCAL} / {@code withCallOptions} / a filter-set
 * {@link CallOptions#getDeadline()}) always wins; then a per-call unlimited opt-out
 * ({@link #unlimited()}); then the global default. A legitimate long call opts out per-call with
 * {@code ClientContext.withCallOptions(ClientDeadline.unlimited(), rpc::longCall)} — no need to
 * disable the default process-wide.
 */
public final class ClientDeadline {

    /** Config key bound by framework autoconfig. */
    public static final String CONFIG_KEY = "rpc.client.defaultDeadlineMillis";

    /** 30s — a hung upstream no longer blocks forever; legitimate long calls must opt out/up. */
    public static final long DEFAULT_DEADLINE_MILLIS = 30_000L;

    private static volatile long defaultDeadlineMillis = DEFAULT_DEADLINE_MILLIS;

    /**
     * Per-call opt-out marker. When set, {@link #apply(CallOptions)} leaves the call deadline-less
     * (unlimited) instead of stamping the default — for legitimately long calls, without disabling
     * the default process-wide. An explicit deadline still outranks it.
     */
    static final CallOptions.Key<Boolean> UNLIMITED_KEY =
            CallOptions.Key.createWithDefault("krpc-client-deadline-unlimited", Boolean.FALSE);

    private ClientDeadline() {
    }

    /** {@code <= 0} = unlimited (restores pre-HARDEN-B2 behaviour). */
    public static void setDefaultDeadlineMillis(long millis) {
        defaultDeadlineMillis = millis;
    }

    public static long getDefaultDeadlineMillis() {
        return defaultDeadlineMillis;
    }

    /**
     * {@link CallOptions#DEFAULT} marked to opt this call out of the default deadline (unlimited).
     * Pass it through {@code ClientContext.withCallOptions(...)} for a legitimately long call.
     */
    public static CallOptions unlimited() {
        return unlimited(CallOptions.DEFAULT);
    }

    /** Mark {@code options} to opt out of the default deadline (unlimited) for this call. */
    public static CallOptions unlimited(CallOptions options) {
        var base = options == null ? CallOptions.DEFAULT : options;
        return base.withOption(UNLIMITED_KEY, Boolean.TRUE);
    }

    /**
     * Return {@code options} with the default deadline applied, or unchanged when disabled
     * ({@code <= 0}), when the caller already set an explicit deadline, or when the caller opted
     * this call out via {@link #unlimited()}.
     */
    public static CallOptions apply(CallOptions options) {
        long millis = defaultDeadlineMillis;
        if (millis <= 0) {
            return options; // unlimited: pre-HARDEN-B2 behaviour
        }
        if (options != null && options.getDeadline() != null) {
            return options; // explicit deadline wins
        }
        if (options != null && Boolean.TRUE.equals(options.getOption(UNLIMITED_KEY))) {
            return options; // per-call opt-out: caller declared this a legitimately unlimited call
        }
        var base = options == null ? CallOptions.DEFAULT : options;
        return base.withDeadlineAfter(millis, TimeUnit.MILLISECONDS);
    }
}
