package tech.krpc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import tech.krpc.util.RecordingLoggerProvider.Event;

/**
 * ADR-0003 (umbrella) requirements 3, 4 and 5 for the shared memo cell: resolution happens once, on
 * the first-use path, and emits exactly one log line — at INFO or above, naming the effective state
 * and its source, reporting the value that actually won a race.
 *
 * <p>Assertions run against the REAL slf4j emission site: {@link RecordingLoggerProvider} is bound as
 * the provider in this module's test scope, so a level regression (INFO/WARN -> DEBUG) or a dropped
 * {@code source=} is visible here. {@link #recorderSeesProductionLogging()} is the positive control —
 * without it, "no line found" could just mean the recorder is not wired.
 */
class FlagSwitchTest {

    @BeforeEach
    void resetRecorder() {
        RecordingLoggerProvider.clear();
    }

    /** Positive control for every "exactly one line" assertion below. */
    @Test
    void recorderSeesProductionLogging() {
        new FlagSwitch("KRPC_PROBE", false).publish(FlagResolution.of(false, false, "true", null));
        assertEquals(1, RecordingLoggerProvider.events().size(),
                "the recording provider must be bound, or the log assertions here prove nothing");
    }

    /**
     * P0/P1 (owner amendment): nothing waits for the logging backend. The hook runs INSIDE the
     * logging call, i.e. while the one resolution line is still being written — and a reader that
     * arrives in that window already gets the canonical value instead of blocking on a monitor or
     * spinning. The value cannot be a different one later, because the claim is immutable, so there
     * is nothing to protect with a lock here.
     */
    @Test
    void callersNeverWaitForTheLoggingBackend() {
        FlagSwitch flag = new FlagSwitch("KRPC_ORDER", true);
        var observedWhileLogging = new AtomicReference<Object>("unset");
        RecordingLoggerProvider.duringEmit(() -> observedWhileLogging.set(flag.resolved()));

        assertFalse(flag.publish(FlagResolution.of(true, false, "false", null)));

        assertEquals(Boolean.FALSE, observedWhileLogging.get(),
                "a reader arriving while the line is written must get the canonical value, not wait");
        assertEquals(Boolean.FALSE, flag.resolved());
        assertEquals(1, RecordingLoggerProvider.events().size());
    }

    /**
     * Requirement 2 + P3' (owner amendment): a broken logging backend must not break the flag read,
     * must not lose the value, and must not leave the line owed FOREVER — the next read of the flag
     * settles it, from the stored canonical resolution, without re-reading the environment. A
     * permanently broken backend means permanently no line; that cost is accepted and documented.
     */
    @Test
    void aFailedEmissionKeepsTheValueAndTheNextReadSettlesTheLine() {
        FlagSwitch flag = new FlagSwitch("KRPC_RETRY", true);
        FlagResolution off = FlagResolution.of(true, false, "false", null);
        RecordingLoggerProvider.failNext(1);

        assertFalse(flag.publish(off), "a logging failure must not break the flag read");
        assertTrue(RecordingLoggerProvider.events().isEmpty(), "the failed line was not recorded");

        assertEquals(Boolean.FALSE, flag.resolved(),
                "the value stands — it is claimed, only its line is owed — and reading the flag"
                + " settles that line");
        Event retried = onlyEvent();
        assertEquals(Level.WARN, retried.level());
        assertEquals("ADR-0003 flag KRPC_RETRY resolved: enabled=false source=property",
                retried.message(), "the owed line names the same resolution, not a fresh read");
        assertEquals(Boolean.FALSE, flag.resolved(), "and the settled value never changes");
    }

    @Test
    void resolvedIsNullUntilTheFirstPublish() {
        FlagSwitch flag = new FlagSwitch("KRPC_TEST", true);
        assertNull(flag.resolved(), "nothing may be resolved before the first-use path runs");
        assertTrue(RecordingLoggerProvider.events().isEmpty(),
                "constructing the memo cell must not resolve — or log — anything");

        assertTrue(flag.publish(FlagResolution.of(true, false, null, null)));
        assertEquals(Boolean.TRUE, flag.resolved(), "the value is memoised after the first publish");
    }

    @Test
    void firstPublishWins_andLaterOnesNeitherChangeTheValueNorLogAgain() {
        FlagSwitch flag = new FlagSwitch("KRPC_TEST", true);
        assertFalse(flag.publish(FlagResolution.of(true, false, "false", null)));

        assertFalse(flag.publish(FlagResolution.of(true, false, "true", null)),
                "a second resolution must return the value already in effect, not its own");
        assertEquals(Boolean.FALSE, flag.resolved());
        assertEquals(1, RecordingLoggerProvider.events().size(),
                "requirement 5: exactly one line per flag, bound to the FIRST effective resolution");
    }

    /** Requirement 5: WARN when the resolution turns OFF a behaviour that defaults ON. */
    @Test
    void pressedKillSwitchLogsWarn_withStateAndSource() {
        new FlagSwitch("rpc.otel.enabled / KRPC_OTEL", true)
                .publish(FlagResolution.of(true, false, "false", null));

        Event event = onlyEvent();
        assertEquals(Level.WARN, event.level(),
                "a pressed kill switch is an unusual state an operator must see without looking");
        assertEquals("ADR-0003 flag rpc.otel.enabled / KRPC_OTEL resolved: enabled=false"
                + " source=property", event.message());
    }

    /** Requirement 5: INFO — never DEBUG, which production does not show — for everything else. */
    @Test
    void everyOtherResolutionLogsInfo_withStateAndSource() {
        record Case(String name, boolean defaultValue, FlagResolution resolution, String expected) {}
        List<Case> cases = List.of(
                new Case("KRPC_OTEL", true, FlagResolution.of(true, false, null, null),
                        "ADR-0003 flag KRPC_OTEL resolved: enabled=true source=default"),
                new Case("KRPC_OTEL", true, FlagResolution.of(true, false, null, " true "),
                        "ADR-0003 flag KRPC_OTEL resolved: enabled=true source=env"),
                new Case("KRPC_MCP", false, FlagResolution.of(false, false, null, "1"),
                        "ADR-0003 flag KRPC_MCP resolved: enabled=true source=env"),
                new Case("KRPC_MCP", false, FlagResolution.of(false, false, null, "yes"),
                        "ADR-0003 flag KRPC_MCP resolved: enabled=false source=unrecognized(yes)"),
                new Case("KRPC_MCP", false,
                        FlagResolution.readFailure(false, new SecurityException()),
                        "ADR-0003 flag KRPC_MCP resolved: enabled=false"
                                + " source=read-failure(SecurityException)"));

        for (Case c : cases) {
            RecordingLoggerProvider.clear();
            new FlagSwitch(c.name(), c.defaultValue()).publish(c.resolution());
            Event event = onlyEvent();
            assertEquals(Level.INFO, event.level(), () -> "level for " + c.expected());
            assertEquals(c.expected(), event.message());
        }
    }

    /**
     * Requirement 5's read failure on a default-ON flag is still a pressed switch: WARN, and the line
     * says WHY it is off, so an operator does not read it as a deliberate disable.
     */
    @Test
    void readFailureOnADefaultOnFlagLogsWarnWithTheFailure() {
        new FlagSwitch("KRPC_OTEL", true)
                .publish(FlagResolution.readFailure(false, new SecurityException()));

        Event event = onlyEvent();
        assertEquals(Level.WARN, event.level());
        assertEquals("ADR-0003 flag KRPC_OTEL resolved: enabled=false"
                + " source=read-failure(SecurityException)", event.message());
    }

    /**
     * Requirement 5, binding point (1): two racing callers produce at most ONE line and any line
     * emitted reports the resolution that actually won — because the line is formatted from the
     * CAS-claimed record, so every caller observes that same value, never its own losing resolution.
     */
    @Test
    void racingCallersProduceOneLineReportingTheWinner() throws Exception {
        int threads = 16;
        for (int round = 0; round < 50; round++) {
            RecordingLoggerProvider.clear();
            FlagSwitch flag = new FlagSwitch("KRPC_RACE", true);
            AtomicInteger resolutions = new AtomicInteger();
            var observed = new ConcurrentLinkedQueue<Boolean>();
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                // Half the callers see a configured OFF, half see an unconfigured default ON: if the
                // log were decoupled from the publish it could report the loser's value.
                boolean off = i % 2 == 0;
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        Boolean memo = flag.resolved();
                        observed.add(memo != null ? memo : flag.publish(resolve(off, resolutions)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "racing callers did not finish");

            Event event = onlyEvent();
            boolean winner = Boolean.TRUE.equals(flag.resolved());
            assertEquals("ADR-0003 flag KRPC_RACE resolved: enabled=" + winner + " source="
                            + (winner ? "default" : "env"), event.message(),
                    "the one line must report the value that actually won");
            assertEquals(winner ? Level.INFO : Level.WARN, event.level());
            for (Boolean seen : observed) {
                assertEquals(winner, seen,
                        "every caller must observe the winning value, never its own resolution");
            }
            assertTrue(resolutions.get() >= 1, "the race must actually have resolved something");
        }
    }

    /**
     * P1 (review R2 finding 1): a logging callback must not be able to build a lock-order cycle
     * between two flags. Both threads are inside their own flag's resolution line when they resolve
     * the OTHER flag from within the logging call — the exact A -> B -> A shape that deadlocked the
     * monitor-based implementation (observed there: {@code BLOCKED/BLOCKED}, both values unpublished,
     * zero lines). A resolution path that takes no lock has nothing to order, so this cannot happen
     * by construction; the test keeps that construction honest.
     */
    @Test
    void twoFlagsResolvedFromInsideEachOthersLoggingBothComplete() throws Exception {
        FlagSwitch a = new FlagSwitch("KRPC_CYCLE_A", false);
        FlagSwitch b = new FlagSwitch("KRPC_CYCLE_B", false);
        FlagResolution on = FlagResolution.of(false, false, "true", null);
        var bothEmitting = new CyclicBarrier(2);
        var hookFailures = new ConcurrentLinkedQueue<Throwable>();
        RecordingLoggerProvider.duringEmit(() -> {
            try {
                bothEmitting.await(30, TimeUnit.SECONDS);
                if ("cycle-a".equals(Thread.currentThread().getName())) {
                    b.publish(on);
                } else {
                    a.publish(on);
                }
            } catch (Exception failure) {
                hookFailures.add(failure);
            }
        });

        var done = new CountDownLatch(2);
        Thread.ofVirtual().name("cycle-a").start(() -> {
            try {
                a.publish(on);
            } finally {
                done.countDown();
            }
        });
        Thread.ofVirtual().name("cycle-b").start(() -> {
            try {
                b.publish(on);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS),
                "a logging callback formed a cycle between two flags: neither resolution completed");
        assertTrue(hookFailures.isEmpty(), () -> "cross-flag resolution failed: " + hookFailures);
        assertEquals(Boolean.TRUE, a.resolved(), "flag A must have published its value");
        assertEquals(Boolean.TRUE, b.resolved(), "flag B must have published its value");
        assertEquals(Set.of("ADR-0003 flag KRPC_CYCLE_A resolved: enabled=true source=property",
                        "ADR-0003 flag KRPC_CYCLE_B resolved: enabled=true source=property"),
                RecordingLoggerProvider.events().stream().map(Event::message)
                        .collect(Collectors.toSet()),
                "exactly one line per flag, each describing its own resolution");
        assertEquals(2, RecordingLoggerProvider.events().size(), "no flag logged twice");
    }

    /**
     * P4 (review R2 finding 1, second probe): a logging backend that resolves the SAME flag from
     * inside the resolution line must terminate with exactly one line and one value. Under the
     * re-entrant monitor this emitted two contradictory lines ({@code enabled=true} then
     * {@code enabled=false}) because the inner call saw an unpublished state and resolved again.
     */
    @Test
    void aSameFlagResolutionInsideItsOwnLoggingCallProducesNoSecondLine() {
        FlagSwitch flag = new FlagSwitch("KRPC_REENTRY", true);
        FlagResolution canonical = FlagResolution.of(true, false, "false", null);
        FlagResolution other = FlagResolution.of(true, false, "true", null);
        var reentrantValue = new AtomicReference<Object>("unset");
        var hookCalls = new AtomicInteger();
        RecordingLoggerProvider.duringEmit(() -> {
            if (hookCalls.incrementAndGet() == 1) {          // one-shot, as the review probe was
                reentrantValue.set(flag.publish(other));
            }
        });

        assertFalse(flag.publish(canonical), "the outer caller keeps its own resolution");

        assertEquals(Boolean.FALSE, reentrantValue.get(),
                "a re-entrant resolution must return the canonical value, never its own");
        assertEquals("ADR-0003 flag KRPC_REENTRY resolved: enabled=false source=property",
                onlyEvent().message(), "exactly one line, describing the value that stands");
        assertEquals(Boolean.FALSE, flag.resolved());
    }

    /**
     * P4, unbounded variant: the same callback on EVERY emission. Under the re-entrant monitor this
     * recursed until {@code StackOverflowError}; the emission claim makes re-entry a no-op instead.
     */
    @Test
    void anUnboundedSameFlagLoggingCallbackTerminates() {
        FlagSwitch flag = new FlagSwitch("KRPC_REENTRY_LOOP", false);
        FlagResolution on = FlagResolution.of(false, false, "true", null);
        var hookCalls = new AtomicInteger();
        RecordingLoggerProvider.duringEmit(() -> {
            hookCalls.incrementAndGet();
            flag.publish(on);
            flag.resolved();
        });

        assertTrue(flag.publish(on));

        assertEquals(1, hookCalls.get(), "the resolution line must not re-enter itself");
        assertEquals("ADR-0003 flag KRPC_REENTRY_LOOP resolved: enabled=true source=property",
                onlyEvent().message());
    }

    /**
     * P2 (review R2 finding 2): once a resolution has been handed to a caller it is canonical. If the
     * line failed and the accessor's next call arrives with a DIFFERENT resolution — the operator
     * changed the property, or a read that failed now succeeds — the later one must neither become
     * the value nor become the single logged line. The retry describes the first resolution.
     */
    @Test
    void anOwedLineIsSettledWithTheFirstResolutionNotALaterReRead() {
        FlagSwitch flag = new FlagSwitch("KRPC_DRIFT", true);
        RecordingLoggerProvider.failNext(1);

        assertFalse(flag.publish(FlagResolution.of(true, false, "false", null)),
                "the first resolution is the one in effect");
        assertTrue(RecordingLoggerProvider.events().isEmpty(), "its line failed to be written");

        assertFalse(flag.publish(FlagResolution.of(true, false, "true", null)),
                "a later re-read must not replace the resolution already handed to a caller");

        Event settled = onlyEvent();
        assertEquals("ADR-0003 flag KRPC_DRIFT resolved: enabled=false source=property",
                settled.message(), "the one line must describe the canonical first resolution");
        assertEquals(Level.WARN, settled.level());
        assertEquals(Boolean.FALSE, flag.resolved());
    }

    private static FlagResolution resolve(boolean off, AtomicInteger resolutions) {
        resolutions.incrementAndGet();
        return FlagResolution.of(true, false, null, off ? "false" : null);
    }

    private static Event onlyEvent() {
        List<Event> events = RecordingLoggerProvider.events();
        assertEquals(1, events.size(), () -> "expected exactly one resolution line, got: " + events);
        return events.get(0);
    }
}
