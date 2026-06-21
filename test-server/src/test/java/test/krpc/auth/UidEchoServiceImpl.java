package test.krpc.auth;

import tech.krpc.model.RpcResult;
import tech.krpc.server.ServerContext;

/**
 * Returns the uid taken from the current {@link ServerContext}, which after the migration is
 * read off {@code io.grpc.Context.current()} rather than a bare ThreadLocal.
 */
public class UidEchoServiceImpl implements UidEchoService {

    @Override
    public RpcResult<String> whoAmI() {
        return RpcResult.ok(ServerContext.current().uid());
    }
}
