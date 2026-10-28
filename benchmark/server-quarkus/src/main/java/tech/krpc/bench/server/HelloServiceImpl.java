package tech.krpc.bench.server;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;

import tech.krpc.bench.contract.HelloReply;
import tech.krpc.bench.contract.HelloRequest;
import tech.krpc.bench.contract.HelloService;
import tech.krpc.model.RpcResult;

/**
 * Container wiring (SPEC §10): {@code @ApplicationScoped @Startup}. Quarkus
 * discovers this bean, sees it implements an {@code @RpcService} interface (from
 * the indexed contract jar), and exposes it on the gRPC + HTTP gateway port.
 *
 * <p>The body is deliberately trivial (no DB, no auth, no allocation beyond the
 * reply) so the measured cost is transport + request dispatch on the server
 * executor — the BENCH-001 tested dimensions — and nothing else.
 */
@ApplicationScoped
@Startup
public class HelloServiceImpl implements HelloService {

    @Override
    public RpcResult<HelloReply> hello(HelloRequest req) {
        return RpcResult.ok(new HelloReply(
                "Hello, " + req.getName() + "!",
                System.currentTimeMillis()));
    }
}
