package tech.krpc.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;

/**
 * HARDEN-B3 fix #2 (terminal-method idempotency) + fix #3 (cancelled short-circuit) for
 * {@link UnaryCallObserver}.
 *
 * <p>Package {@code tech.krpc.server} on purpose: the observer's constructor and its {@code cancelled}
 * field are package-private. No Mockito on this classpath, so the transport is a hand-rolled
 * {@link FakeServerCall} that faithfully mimics real gRPC: closing an already-closed call throws
 * {@code IllegalStateException("call already closed")}.
 *
 * <p><b>THE #2 RED LINE</b> (test {@link #firstRealError_wins_secondSpuriousDropped()}): when a real
 * business error is followed by a spurious second error (as when {@code UnaryMethod}'s catch fires
 * after {@code onCompleted}'s {@code close()} threw), the FIRST real error must reach the client and
 * the second must be silently dropped — never a second {@code close()} that throws
 * "call already closed" and masks the real status.
 */
class UnaryCallObserverIdempotencyTest {

    /**
     * Minimal in-memory {@link ServerCall} standing in for the real gRPC transport. It records what
     * the observer did (close count/status, send count) and, like real gRPC, throws if closed twice —
     * so a broken idempotency guard trips it immediately rather than passing silently.
     */
    static final class FakeServerCall extends ServerCall<InputProto, OutputProto> {
        int closeCount;                 // successful close() calls recorded
        int closeAttempts;              // every entry into close(), even ones that throw
        Status lastCloseStatus;
        int sendMessageCount;
        boolean cancelled;              // drives isCancelled(); set by the test
        boolean throwOnFirstClose;      // simulate close() failing on a broken/cancelled call

        private boolean closed;
        private boolean firstCloseSeen;

        @Override
        public void request(int numMessages) {
            // no flow control in the fake
        }

        @Override
        public void sendHeaders(Metadata headers) {
            // headers are irrelevant to the terminal-method contract under test
        }

        @Override
        public void sendMessage(OutputProto message) {
            sendMessageCount++;
        }

        @Override
        public void close(Status status, Metadata trailers) {
            closeAttempts++;
            if (throwOnFirstClose && !firstCloseSeen) {
                firstCloseSeen = true;
                // records nothing: mimic close() failing before the transport marks the call closed
                throw new RuntimeException("transport gone");
            }
            if (closed) {
                throw new IllegalStateException("call already closed");
            }
            closed = true;
            closeCount++;
            lastCloseStatus = status;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public MethodDescriptor<InputProto, OutputProto> getMethodDescriptor() {
            return null; // never touched by the terminal methods under test
        }
    }

    @Test
    void onCompleted_thenOnError_isNoOp_okStatusWins() {
        var fake = new FakeServerCall();
        var obs = new UnaryCallObserver(fake, new Metadata());

        obs.onCompleted();
        assertEquals(1, fake.closeCount);
        assertEquals(Status.Code.OK, fake.lastCloseStatus.getCode());

        // 2nd terminal signal is a no-op — pre-fix it re-closed and threw "call already closed".
        assertDoesNotThrow(() -> obs.onError(new RuntimeException("x")));
        assertEquals(1, fake.closeCount);
        assertEquals(Status.Code.OK, fake.lastCloseStatus.getCode());
    }

    @Test
    void onError_thenOnCompleted_isNoOp_errorStatusWins() {
        var fake = new FakeServerCall();
        var obs = new UnaryCallObserver(fake, new Metadata());

        var bizErr = Status.PERMISSION_DENIED.withDescription("nope").asRuntimeException();
        obs.onError(bizErr);
        assertEquals(1, fake.closeCount);
        assertEquals(Status.Code.PERMISSION_DENIED, fake.lastCloseStatus.getCode());

        assertDoesNotThrow(obs::onCompleted);
        assertEquals(1, fake.closeCount);
        assertEquals(Status.Code.PERMISSION_DENIED, fake.lastCloseStatus.getCode());
    }

    @Test
    void firstRealError_wins_secondSpuriousDropped() {
        // THE #2 RED LINE. The first, real business error must reach the client; the spurious second
        // must be dropped, never masking the first — and never as a second close() throwing.
        var fake = new FakeServerCall();
        var obs = new UnaryCallObserver(fake, new Metadata());

        var first = Status.INVALID_ARGUMENT.withDescription("REAL").asRuntimeException();
        var second = Status.INTERNAL.withDescription("SPURIOUS").asRuntimeException();

        obs.onError(first);
        assertDoesNotThrow(() -> obs.onError(second)); // no IllegalStateException from a 2nd close

        assertEquals(1, fake.closeCount);
        assertEquals(Status.Code.INVALID_ARGUMENT, fake.lastCloseStatus.getCode());
        assertNotNull(fake.lastCloseStatus.getDescription());
        assertTrue(fake.lastCloseStatus.getDescription().contains("REAL"),
                () -> "the first real error must win, got: " + fake.lastCloseStatus.getDescription());
        assertEquals(false, fake.lastCloseStatus.getDescription().contains("SPURIOUS"));
    }

    @Test
    void doubleOnError_closesExactlyOnce_noThrow() {
        var fake = new FakeServerCall();
        var obs = new UnaryCallObserver(fake, new Metadata());

        var e = new RuntimeException("boom");
        obs.onError(e);
        assertDoesNotThrow(() -> obs.onError(e));
        assertEquals(1, fake.closeCount);
    }

    @Test
    void cancelledCall_terminalMethodsDoNotClose_andOnNextDoesNotSend() {
        // fix #3: a cancelled call is already closed transport-side; the observer must NOT call
        // close()/sendMessage() (which would race the transport close). Separate observers keep the
        // idempotency flags clean so each terminal method reaches the cancelled short-circuit.
        var fake = new FakeServerCall();
        fake.cancelled = true;

        var errObs = new UnaryCallObserver(fake, new Metadata());
        assertDoesNotThrow(() -> errObs.onError(new RuntimeException("x")));

        var completeObs = new UnaryCallObserver(fake, new Metadata());
        assertDoesNotThrow(completeObs::onCompleted);

        assertEquals(0, fake.closeCount, "cancelled call must not be closed by the observer");

        // onNext short-circuits before touching ServerContext.current() (which would NPE here with no
        // attached io.grpc.Context) — so the fact it does not throw is itself part of the contract.
        var nextObs = new UnaryCallObserver(fake, new Metadata());
        var out = OutputProto.newBuilder().build();
        assertDoesNotThrow(() -> nextObs.onNext(out));
        assertEquals(0, fake.sendMessageCount, "cancelled call must not have a message sent");
    }

    @Test
    void onCompletedThrowingClose_leavesGuardSet_soLaterOnErrorIsNoOp() {
        // The UnaryMethod catch path: onCompleted()'s close() throws. Because `completed` is set
        // BEFORE close(), a following onError() sees the guard and is a no-op — it must NOT attempt a
        // second close (which pre-fix would either throw or overwrite the status from the catch block).
        var fake = new FakeServerCall();
        fake.throwOnFirstClose = true;
        var obs = new UnaryCallObserver(fake, new Metadata());

        assertThrows(RuntimeException.class, obs::onCompleted);
        assertEquals(1, fake.closeAttempts);
        assertEquals(0, fake.closeCount); // the throwing close recorded nothing

        assertDoesNotThrow(() -> obs.onError(new RuntimeException("late")));
        assertEquals(1, fake.closeAttempts, "no second close attempt after a throwing onCompleted");
        assertEquals(0, fake.closeCount);
    }
}
