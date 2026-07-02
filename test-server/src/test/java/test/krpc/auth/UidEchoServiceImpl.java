package test.krpc.auth;

import java.util.concurrent.atomic.AtomicInteger;

import tech.krpc.model.RpcResult;
import tech.krpc.server.ServerContext;

/**
 * Returns the uid taken from the current {@link ServerContext}, which after the migration is
 * read off {@code io.grpc.Context.current()} rather than a bare ThreadLocal.
 */
public class UidEchoServiceImpl implements UidEchoService {

    // AGENT-001 Fix 1/2: counts service-body executions so tests can prove the body did NOT
    // run when the credential gate rejected the call (and that the counter is actually wired).
    private final AtomicInteger calls = new AtomicInteger();

    /** Number of times a service body actually executed (whoAmI or boom). */
    public int calls() {
        return calls.get();
    }

    @Override
    public RpcResult<String> whoAmI() {
        calls.incrementAndGet();
        return RpcResult.ok(ServerContext.current().uid());
    }

    @Override
    public RpcResult<String> boom() {
        // AGENT-001 Fix 2: credential passes, then the body throws — exercises the
        // invokeWeb finally/detach path (clean context after a service exception).
        calls.incrementAndGet();
        throw new IllegalStateException("boom");
    }
}
