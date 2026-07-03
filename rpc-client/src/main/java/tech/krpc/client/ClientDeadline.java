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
 * ms. {@code 0} or negative = unlimited = pre-HARDEN-B2 behaviour. An explicit deadline
 * ({@code OPTION_LOCAL} / {@code withCallOptions} / a filter-set {@link CallOptions#getDeadline()})
 * always wins.
 */
public final class ClientDeadline {

    /** Config key bound by framework autoconfig. */
    public static final String CONFIG_KEY = "rpc.client.defaultDeadlineMillis";

    /** 30s — a hung upstream no longer blocks forever; legitimate long calls must opt out/up. */
    public static final long DEFAULT_DEADLINE_MILLIS = 30_000L;

    private static volatile long defaultDeadlineMillis = DEFAULT_DEADLINE_MILLIS;

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
     * Return {@code options} with the default deadline applied, or unchanged when disabled
     * ({@code <= 0}) or when the caller already set an explicit deadline.
     */
    public static CallOptions apply(CallOptions options) {
        long millis = defaultDeadlineMillis;
        if (millis <= 0) {
            return options; // unlimited: pre-HARDEN-B2 behaviour
        }
        if (options != null && options.getDeadline() != null) {
            return options; // explicit deadline wins
        }
        var base = options == null ? CallOptions.DEFAULT : options;
        return base.withDeadlineAfter(millis, TimeUnit.MILLISECONDS);
    }
}
