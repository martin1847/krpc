package tech.krpc.server;

import io.grpc.Metadata;
import tech.krpc.internal.InputProto;

/**
 * ADR-0004 (AGENT-001 P0): narrow synchronous dispatch seam for the HTTP agent endpoints.
 *
 * <p>Decouples the web/agent layer from {@link UnaryMethod}, whose type signature pulls in
 * {@code io.grpc.stub.ServerCalls} (an {@code implementation}-scoped grpc-stub dependency
 * not exported to downstream modules). Callers see only this interface plus
 * {@code rpc-common}/grpc-api types.
 */
@FunctionalInterface
public interface WebInvoker {

    /**
     * Run the request through the same credential + filter-chain path as the gRPC entry.
     *
     * @throws Throwable on credential failure or any error raised by filters / the method.
     */
    ServerResult invokeWeb(InputProto input, Metadata headers) throws Throwable;
}
