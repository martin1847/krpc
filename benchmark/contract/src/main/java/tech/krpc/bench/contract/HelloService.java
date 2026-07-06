package tech.krpc.bench.contract;

import tech.krpc.annotation.RpcService;
import tech.krpc.model.RpcResult;

/**
 * The benchmark hot path: a single DB-free, JWT-free unary call with a small
 * request/response — mirrors examples/quickstart's Hello shape so BENCH-001
 * numbers stay comparable to the "typical small-message unary" methodology of
 * IOURING-001. No @UnsafeWeb / no validation: the driver calls over gRPC only,
 * and every per-call cost that is NOT the tested dimension (executor, transport)
 * is kept out of the path so it cannot mask the signal.
 *
 * <p>Service name derivation (SPEC §5): {@code HelloService} -> {@code Hello};
 * with {@code rpc.server.app=bench} the call path is {@code bench/Hello/hello}.
 */
@RpcService(description = "krpc benchmark hello service")
public interface HelloService {

    RpcResult<HelloReply> hello(HelloRequest req);
}
