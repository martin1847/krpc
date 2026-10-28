package test.krpc.stream;

import tech.krpc.annotation.RpcService;
import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.model.RpcResult;

/**
 * Stream-cap integration-test fixture. Its single method deliberately BLOCKS inside the
 * server until the test releases it, so the test can hold requests in-flight on one channel
 * and observe how many the server ran concurrently. Used to prove that
 * {@code RpcServerBuilder.Builder.maxConcurrentCallsPerConnection(N)} really bounds the number
 * of concurrent HTTP/2 streams per connection (excess streams are QUEUED client-side, not
 * rejected).
 */
@UnsafeWeb
@RpcService(description = "blocks server-side to observe per-connection concurrency cap")
public interface BlockingCapService {

    /**
     * Enters the server, records the current concurrency, then blocks until the test releases
     * the gate. Returns the in-flight count observed at entry (never the cause of the assertion,
     * only diagnostic).
     */
    RpcResult<Integer> occupy();
}
