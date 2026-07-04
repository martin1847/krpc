package tech.krpc.http.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/**
 * HARDEN-B4 fix round 2 — per-connection queue cleanup on channel close for
 * {@link AbstractHttpHandler}.
 *
 * <p>O6 (fix round 1) added a per-connection FIFO ({@code ConnState.queue}): while request A is in
 * flight, B/C pipelined behind it wait, and a slot is freed only when A's response is written
 * ({@code drain}). Before this fix there was no close cleanup — no {@code channelInactive}. So if a
 * client disconnected with A active and B/C queued, the queued tasks were either leaked forever
 * (A hangs, its slot never frees) or, when A completed, dispatched and written on an already-dead
 * channel.
 *
 * <p>This drives the handler through an {@link EmbeddedChannel}, whose {@code writeInbound} fires
 * {@code channelRead0} for every pipelined message (A dispatched to a virtual thread, B/C queued)
 * and whose {@code close()} fires {@code channelInactive} synchronously — the exact
 * A-active-plus-B/C-queued-then-disconnect race, deterministically. It defends the red line:
 * after close, nothing is dispatched or written on the closed channel and the queue is released.
 */
class HttpChannelCloseCleanupTest {

    @Test
    void channelCloseDropsQueuedWorkAndNeverDispatchesOrWritesOnClosedChannel() throws Exception {
        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();
        AtomicInteger cCount = new AtomicInteger();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch aRelease = new CountDownLatch(1);

        CloseTestHandler handler = new CloseTestHandler(aStarted, aRelease, aCount, bCount, cCount);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        // A, B, C pipelined "in one segment": every message reaches channelRead0. A is dispatched to
        // the VT (active=true, autoRead off) and B/C are queued — both happen synchronously here.
        ch.writeInbound(req("/a"), req("/b"), req("/c"));

        assertTrue(aStarted.await(2, TimeUnit.SECONDS), "A never began dispatching");
        // A is active (blocked on aRelease); B and C are queued behind it, not yet dispatched.
        assertEquals(1, aCount.get(), "A must have dispatched");
        assertEquals(0, bCount.get(), "B must still be queued, not dispatched, while A is in flight");
        assertEquals(0, cCount.get(), "C must still be queued, not dispatched, while A is in flight");

        // Client disconnects while A is in flight and B/C are queued.
        ch.close().sync();
        assertFalse(ch.isActive(), "channel must be closed");

        // Let A finish. Its handler completes on the VT and schedules its response write back on the
        // eventLoop; join the VT so that scheduled task is visible before we pump the loop.
        aRelease.countDown();
        Thread vt = handler.aThread;
        if (vt != null) {
            vt.join(2000);
        }

        // Pump the eventLoop. Post-fix: A's write is suppressed (channel closed) and the queue was
        // dropped on close, so drain never dispatches B/C. Pre-fix: A's write + onDone would re-drive
        // drain and dispatch B (then C) on the dead channel. Poll long enough that such a regression
        // (B/C on a virtual thread) would surface.
        for (int i = 0; i < 40 && bCount.get() == 0 && cCount.get() == 0; i++) {
            ch.runPendingTasks();
            Thread.sleep(25);
        }

        assertEquals(0, bCount.get(), "queued B must be dropped on close, never dispatched on a closed channel");
        assertEquals(0, cCount.get(), "queued C must be dropped on close, never dispatched on a closed channel");
        assertNull(ch.readOutbound(), "no response may be written to a closed channel");
        assertDoesNotThrow(ch::checkException,
                "writing/dispatching on the closed channel must not raise (e.g. ClosedChannelException)");
    }

    private static FullHttpRequest req(String path) {
        ByteBuf content = Unpooled.copiedBuffer("{}", StandardCharsets.UTF_8);
        FullHttpRequest r =
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, path, content);
        r.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        r.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        return r;
    }

    /**
     * Registers /a (blocks on a latch so it holds the in-flight slot while B/C queue), /b and /c
     * (count-only). {@code aThread} captures A's virtual thread so the test can join it.
     */
    static final class CloseTestHandler extends AbstractHttpHandler {
        volatile Thread aThread;

        CloseTestHandler(CountDownLatch aStarted, CountDownLatch aRelease,
                AtomicInteger aCount, AtomicInteger bCount, AtomicInteger cCount) {
            postMap.put("/a", new GateHandler("/a", aCount, aStarted, aRelease));
            postMap.put("/b", new CountHandler("/b", bCount));
            postMap.put("/c", new CountHandler("/c", cCount));
        }

        @Override
        public Validator getValidator() {
            return null;
        }

        @Override
        public void initHandler() {
            // endpoints registered in the constructor
        }

        /** Blocks inside handle() until released — holds the in-flight slot so B/C stay queued. */
        private final class GateHandler implements PostHandler<String> {
            private final String path;
            private final AtomicInteger count;
            private final CountDownLatch started;
            private final CountDownLatch release;

            GateHandler(String path, AtomicInteger count, CountDownLatch started, CountDownLatch release) {
                this.path = path;
                this.count = count;
                this.started = started;
                this.release = release;
            }

            @Override
            public Class<String> getParamClass() {
                return String.class;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public boolean useValidator() {
                return false;
            }

            @Override
            public byte[] handle(String param, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
                aThread = Thread.currentThread();
                count.incrementAndGet();
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "{\"a\":true}".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String contextType() {
                return AbstractHttpHandler.TYPE_JSON;
            }
        }

        /** Records that it was dispatched — the teeth: it must NOT run once the channel is closed. */
        private static final class CountHandler implements PostHandler<String> {
            private final String path;
            private final AtomicInteger count;

            CountHandler(String path, AtomicInteger count) {
                this.path = path;
                this.count = count;
            }

            @Override
            public Class<String> getParamClass() {
                return String.class;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public boolean useValidator() {
                return false;
            }

            @Override
            public byte[] handle(String param, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
                count.incrementAndGet();
                return "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String contextType() {
                return AbstractHttpHandler.TYPE_JSON;
            }
        }
    }
}
