package tech.krpc.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * TEST SCOPE ONLY — an SLF4J provider that records what production code logged, so ADR-0003
 * requirement 5 ("exactly one line, at INFO or above, naming the effective state and its source")
 * can be asserted at the real emission site rather than through a test-only seam in
 * {@link FlagSwitch}. Registered via {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider};
 * without it slf4j binds its NOP provider and every level would report "disabled", which would make
 * a level regression (INFO -> DEBUG) invisible.
 * <p>All levels report enabled: the recorder must observe what the code asks for, not what a
 * backend configuration would filter. Two hooks make the emission itself testable:
 * {@link #failNext(int)} simulates a broken appender/backend, and {@link #duringEmit(Runnable)} runs
 * inside the logging call so a test can observe what the rest of the system sees while the line is
 * being written.
 */
public final class RecordingLoggerProvider implements SLF4JServiceProvider {

    /** One recorded logging call. */
    public record Event(String logger, Level level, String message) {}

    private static final List<Event> EVENTS = new CopyOnWriteArrayList<>();

    /** Number of upcoming logging calls that must throw, simulating a broken backend. */
    private static final AtomicInteger FAILURES = new AtomicInteger();

    /** Runs inside the logging call, before the event is recorded. */
    private static volatile Runnable duringEmit = () -> { };

    private final ILoggerFactory loggerFactory = RecordingLogger::new;
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new BasicMDCAdapter();

    /** Everything logged since the last {@link #clear()}. */
    public static List<Event> events() {
        return List.copyOf(EVENTS);
    }

    /** Makes the next {@code count} logging calls throw, as a broken appender would. */
    public static void failNext(int count) {
        FAILURES.set(count);
    }

    /** Installs a hook run inside the logging call (used to prove publish-after-log ordering). */
    public static void duringEmit(Runnable hook) {
        duringEmit = hook;
    }

    public static void clear() {
        EVENTS.clear();
        FAILURES.set(0);
        duringEmit = () -> { };
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
        // no state to build up
    }

    private static final class RecordingLogger extends LegacyAbstractLogger {

        private static final long serialVersionUID = 1L;

        RecordingLogger(String name) {
            this.name = name;
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern,
                                                   Object[] arguments, Throwable throwable) {
            duringEmit.run();
            if (FAILURES.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new IllegalStateException("simulated appender failure");
            }
            EVENTS.add(new Event(name, level,
                    MessageFormatter.basicArrayFormat(pattern, arguments)));
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        public boolean isTraceEnabled() {
            return true;
        }

        @Override
        public boolean isDebugEnabled() {
            return true;
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }
    }
}
