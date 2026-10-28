package tech.krpc.client.spring.testsvc;

import tech.krpc.annotation.RpcService;
import tech.krpc.model.RpcResult;

/**
 * Test-only RPC service interface.
 *
 * <p>Exists solely so {@code RpcClientScannerConfigurer}'s Guava {@code ClassPath} scan finds a
 * top-level interface annotated with {@link RpcService} under package
 * {@code tech.krpc.client.spring.testsvc}. Without at least one such interface the scanner hits
 * {@code clzSet.isEmpty() -> continue} and registers nothing, so the C8 factory-bean assertions
 * would have nothing to bite on.
 *
 * <p>It is never actually invoked over the wire; the C8 tests only exercise factory registration,
 * default-port resolution, and channel shutdown.
 */
@RpcService
public interface DemoRpc {
    RpcResult<String> hello();
}
