package test.krpc.stream;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import tech.krpc.model.RpcResult;

/**
 * Server-side impl for the stream-cap test. On every request it:
 * <ol>
 *   <li>increments a live in-flight counter and updates a high-water mark (the peak number of
 *       requests that were ever executing simultaneously),</li>
 *   <li>counts down an {@code entered} latch so the test can wait until N requests are actually
 *       blocked in the method body,</li>
 *   <li>blocks on a {@code release} latch controlled by the test,</li>
 *   <li>decrements the in-flight counter on the way out.</li>
 * </ol>
 * The high-water mark is the meaningful signal: with the connection cap set to N, the client
 * transport must never open more than N concurrent streams, so no more than N request bodies
 * can be running here at once.
 */
public class BlockingCapServiceImpl implements BlockingCapService {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger highWater = new AtomicInteger();

    /** Counts down once per request entry; test awaits it to know N requests are blocked. */
    private final CountDownLatch entered;
    /** Held closed by the test; every request body blocks here until the test opens it. */
    private final CountDownLatch release = new CountDownLatch(1);

    BlockingCapServiceImpl(int expectedConcurrentEntries) {
        this.entered = new CountDownLatch(expectedConcurrentEntries);
    }

    @Override
    public RpcResult<Integer> occupy() {
        int now = inFlight.incrementAndGet();
        highWater.accumulateAndGet(now, Math::max);
        entered.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RpcResult.error(13, "interrupted while blocked");
        } finally {
            inFlight.decrementAndGet();
        }
        return RpcResult.ok(now);
    }

    /** Blocks until {@code expectedConcurrentEntries} requests have entered {@link #occupy()}. */
    void awaitEntered() throws InterruptedException {
        entered.await();
    }

    /** Same, but bounded — returns false on timeout instead of hanging the test. */
    boolean awaitEntered(long timeout, java.util.concurrent.TimeUnit unit)
            throws InterruptedException {
        return entered.await(timeout, unit);
    }

    /** Opens the gate so every blocked request drains and returns. */
    void releaseAll() {
        release.countDown();
    }

    /** Peak number of requests observed executing concurrently. */
    int highWater() {
        return highWater.get();
    }
}
