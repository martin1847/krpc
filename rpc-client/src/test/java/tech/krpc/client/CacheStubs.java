package tech.krpc.client;

import java.lang.reflect.Method;

import tech.krpc.annotation.RpcService;
import tech.krpc.common.MethodStub;

/** Builds {@link MethodStub}s over {@link CacheTestRpc} for the CacheManager tests. */
final class CacheStubs {

    private CacheStubs() {
    }

    /** Stub whose returnType == byte[].class → CacheManager takes the byte[] path. */
    static MethodStub bytesStub() {
        return stub("bytesMethod", byte[].class);
    }

    /** Stub whose returnType == String.class → CacheManager takes the utf8 path. */
    static MethodStub utf8Stub() {
        return stub("utf8Method", String.class);
    }

    private static MethodStub stub(String methodName, Class<?> paramType) {
        try {
            Method m = CacheTestRpc.class.getMethod(methodName, paramType);
            RpcService ann = CacheTestRpc.class.getAnnotation(RpcService.class);
            MethodStub stub = new MethodStub(ann, "CacheTestRpc", m);
            stub.setExpireSeconds(60);
            return stub;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("test stub method missing: " + methodName, e);
        }
    }
}
