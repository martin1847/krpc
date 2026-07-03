package tech.krpc.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.grpc.CallOptions;

/**
 * O2 (HARDEN-B2): {@link ClientDeadline} is the single authority for the default call deadline.
 *
 * <p>Contracts defended: a call with no deadline gets the configured default applied; an explicit
 * caller deadline is preserved (never overwritten); a {@code <= 0} default means unlimited (no
 * deadline). Pre-fix outbound calls used {@code CallOptions.DEFAULT} with no deadline, so a hung
 * upstream blocked forever — the "default applies a non-null deadline" assertion kills that.
 *
 * <p>The default is a global static, so it is saved in {@link #saveDefault()} and restored in
 * {@link #restoreDefault()} to keep tests order-independent and prevent bleed into other suites.
 */
class ClientDeadlineTest {

    private long saved;

    @BeforeEach
    void saveDefault() {
        saved = ClientDeadline.getDefaultDeadlineMillis();
    }

    @AfterEach
    void restoreDefault() {
        ClientDeadline.setDefaultDeadlineMillis(saved);
    }

    @Test
    void defaultAppliesNonNullDeadline() {
        ClientDeadline.setDefaultDeadlineMillis(ClientDeadline.DEFAULT_DEADLINE_MILLIS);

        CallOptions applied = ClientDeadline.apply(CallOptions.DEFAULT);

        assertNotNull(applied.getDeadline(),
                "with a positive default and no caller deadline, a deadline must be applied");
    }

    @Test
    void explicitDeadlineIsPreserved() {
        ClientDeadline.setDefaultDeadlineMillis(ClientDeadline.DEFAULT_DEADLINE_MILLIS);
        CallOptions opt = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);

        CallOptions applied = ClientDeadline.apply(opt);

        // The caller's exact Deadline instance must survive — not be replaced by the 30s default.
        assertSame(opt.getDeadline(), applied.getDeadline(),
                "an explicit caller deadline must win over the configured default");
    }

    @Test
    void zeroDefaultMeansUnlimited() {
        ClientDeadline.setDefaultDeadlineMillis(0);

        CallOptions applied = ClientDeadline.apply(CallOptions.DEFAULT);

        assertNull(applied.getDeadline(),
                "a zero default disables the deadline (unlimited, pre-HARDEN-B2 behaviour)");
    }

    @Test
    void negativeDefaultMeansUnlimited() {
        ClientDeadline.setDefaultDeadlineMillis(-1);

        CallOptions applied = ClientDeadline.apply(CallOptions.DEFAULT);

        assertNull(applied.getDeadline(),
                "a negative default disables the deadline (unlimited)");
    }

    @Test
    void customPositiveDefaultIsApplied() {
        // Prove the value actually flows through, not just non-null: a 1s default must yield a
        // deadline with ~<=1s remaining, distinct from the 30s compiled-in default.
        ClientDeadline.setDefaultDeadlineMillis(1000);

        CallOptions applied = ClientDeadline.apply(CallOptions.DEFAULT);

        assertNotNull(applied.getDeadline(), "custom positive default must apply a deadline");
        long remainingMs = applied.getDeadline().timeRemaining(TimeUnit.MILLISECONDS);
        assertTrue(remainingMs <= 1000,
                () -> "custom 1s default should bound remaining time to ~1000ms, was " + remainingMs);
        assertTrue(remainingMs > 0,
                () -> "custom 1s default should leave positive remaining time, was " + remainingMs);
    }
}
