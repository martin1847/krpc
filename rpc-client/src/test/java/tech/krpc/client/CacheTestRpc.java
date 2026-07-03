package tech.krpc.client;

import tech.krpc.annotation.RpcService;
import tech.krpc.model.RpcResult;

/**
 * Test-only {@link RpcService} used to build {@link tech.krpc.common.MethodStub}s for the
 * HARDEN-B2 CacheManager regression tests. {@link #bytesMethod(byte[])} returns
 * {@code RpcResult<byte[]>} → stub.returnType == byte[].class → the byte[] cache path
 * (md5 key, clone-on-set/get). The cache-key logic keys on the InputProto dataCase, not the
 * stub's return type, so a single stub reaches every key shape (u:/b:/n:) in the tests.
 */
@RpcService("CacheTestRpc")
interface CacheTestRpc {

    RpcResult<byte[]> bytesMethod(byte[] a);
}
