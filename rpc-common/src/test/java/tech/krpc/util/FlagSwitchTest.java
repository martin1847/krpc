package tech.krpc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
     * Requirement 5, atomicity: the line is emitted by the same guarded step that publishes the value,
     * so racing callers produce exactly ONE line, it reports the value that won, and every caller
     * observes that same value — never its own losing resolution.
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
