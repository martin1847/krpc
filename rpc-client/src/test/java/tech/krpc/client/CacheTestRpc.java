package tech.krpc.client;

import tech.krpc.annotation.RpcService;
import tech.krpc.model.RpcResult;

/**
 * Test-only {@link RpcService} used to build {@link tech.krpc.common.MethodStub}s for the
 * HARDEN-B2 CacheManager regression tests. Two methods so both cache paths are exercised:
 * <ul>
 *   <li>{@link #bytesMethod(byte[])} returns {@code RpcResult<byte[]>} → stub.returnType ==
 *       byte[].class → the byte[] cache path (md5 key, clone-on-set/get).</li>
 *   <li>{@link #utf8Method(String)} returns {@code RpcResult<String>} → the utf8 path.</li>
 * </ul>
 */
@RpcService("CacheTestRpc")
interface CacheTestRpc {

    RpcResult<byte[]> bytesMethod(byte[] a);

    RpcResult<String> utf8Method(String a);
}
