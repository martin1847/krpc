package tech.krpc.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;
import tech.krpc.internal.SerialEnum;

/**
 * O2 (HARDEN-B2, fix round 1): prove the default deadline is actually WIRED into the outbound call
 * path, not merely computable in isolation. {@link ClientDeadlineTest} exercises
 * {@link ClientDeadline#apply}; this drives the real {@code MethodCallProxyHandler.makeCall} seam
 * and asserts that whatever {@code apply} produced is what reaches {@link io.grpc.Channel#newCall}.
 *
 * <p>A capturing fake {@link ManagedChannel} records the {@link CallOptions} handed to
 * {@code newCall}. A mutation that reverts the wiring to {@code channel.newCall(md,
 * CallOptions.DEFAULT)} (bypassing {@code ClientDeadline.apply}) drops the deadline and turns the
 * first assertion red; a mutation that ignores the per-call opt-out turns the second red.
 */
class ClientDeadlineWiringTest {

    /** Fake channel that captures the CallOptions of the most recent newCall and answers with null. */
    private static final class CapturingChannel extends ManagedChannel {
        volatile CallOptions lastOptions;

        @Override
        public <R, P> ClientCall<R, P> newCall(MethodDescriptor<R, P> methodDescriptor, CallOptions callOptions) {
            this.lastOptions = callOptions;
            return null; // makeCall only wraps/returns the call; the test never sends on it
        }

        @Override
        public String authority() {
            return "test-authority";
        }

        @Override
        public ManagedChannel shutdown() {
            return this;
        }

        @Override
        public ManagedChannel shutdownNow() {
            return this;
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    private CapturingChannel channel;
    private MethodCallProxyHandler<CacheTestRpc> handler;
    private MethodCallProxyHandler<CacheTestRpc>.ChannelMethodInvoker invoker;
    private long savedDefault;

    @BeforeEach
    void setUp() throws Exception {
        savedDefault = ClientDeadline.getDefaultDeadlineMillis();
        ClientDeadline.setDefaultDeadlineMillis(ClientDeadline.DEFAULT_DEADLINE_MILLIS);

        channel = new CapturingChannel();
        handler = new MethodCallProxyHandler<>(
                "wiring-test", channel, CacheTestRpc.class, List.of(), null, SerialEnum.JSON);

        Method bytesMethod = CacheTestRpc.class.getMethod("bytesMethod", byte[].class);
        invoker = handler.stubMap.get(bytesMethod);
        assertNotNull(invoker, "bytesMethod must have a wired invoker");
    }

    @AfterEach
    void tearDown() {
        ClientDeadline.setDefaultDeadlineMillis(savedDefault);
    }

    @Test
    void makeCallRoutesDefaultDeadlineIntoNewCall() {
        // A deadline-less call must reach newCall carrying the applied default deadline.
        invoker.makeCall(CallOptions.DEFAULT);

        assertNotNull(channel.lastOptions, "makeCall must invoke channel.newCall");
        assertNotNull(channel.lastOptions.getDeadline(),
                "the default deadline must be wired into the CallOptions handed to newCall");
        long remaining = channel.lastOptions.getDeadline().timeRemaining(TimeUnit.MILLISECONDS);
        assertTrue(remaining > 0 && remaining <= ClientDeadline.DEFAULT_DEADLINE_MILLIS,
                () -> "wired deadline must reflect the 30s default, remaining was " + remaining);
    }

    @Test
    void makeCallRoutesUnlimitedOptOutIntoNewCall() {
        // The per-call opt-out must also flow through the same seam: no deadline stamped.
        invoker.makeCall(ClientDeadline.unlimited());

        assertNotNull(channel.lastOptions, "makeCall must invoke channel.newCall");
        assertNull(channel.lastOptions.getDeadline(),
                "an unlimited opt-out must reach newCall with no deadline applied");
    }
}
