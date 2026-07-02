package tech.krpc.examples.quickstart;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import tech.krpc.model.RpcResult;

/**
 * Container wiring for Quarkus (SPEC §10): {@code @ApplicationScoped @Startup}.
 * The framework discovers this bean, sees it implements an {@code @RpcService}
 * interface, and exposes it on the gRPC + HTTP gateway port (default 50051).
 */
@ApplicationScoped
@Startup
public class HelloServiceImpl implements HelloService {

    @Override
    public RpcResult<HelloReply> hello(HelloRequest req) {
        // Business success: ok(nonNullData). Business failures would instead
        // return RpcResult.error(code>0, msg) — see SPEC §3.
        return RpcResult.ok(new HelloReply(
                "Hello, " + req.getName() + "!",
                System.currentTimeMillis()));
    }
}
