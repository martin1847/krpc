package test.krpc.auth;

import tech.krpc.annotation.RpcService;
import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.model.RpcResult;

/**
 * Auth integration test fixture: a credential-required service whose method echoes the
 * authenticated user id resolved from the per-request {@code ServerContext}. Used to prove
 * that {@code ServerContext.current().uid()} flows correctly after the migration of the
 * server context from a bare {@code ThreadLocal} to {@code io.grpc.Context}.
 */
@UnsafeWeb(requireCredential = true)
@RpcService(description = "echo authenticated uid for io.grpc.Context migration test")
public interface UidEchoService {

    RpcResult<String> whoAmI();

    /**
     * AGENT-001 Fix 2: a credential-required method whose body throws, used to exercise the
     * {@code UnaryMethod.invokeWeb} finally/detach path (clean context after a service exception).
     */
    RpcResult<String> boom();
}
