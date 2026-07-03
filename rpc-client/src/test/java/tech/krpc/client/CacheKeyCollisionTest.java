package tech.krpc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import tech.krpc.common.MethodStub;
import tech.krpc.internal.InputProto;

/**
 * C2 (HARDEN-B2): {@link CacheManager#cacheKey(MethodStub, InputProto)} must distinguish inputs by
 * their actual payload and dataCase.
 *
 * <p>Pre-fix the key was derived from {@code input.getUtf8()} alone, which is {@code ""} for every
 * byte[] input (the payload rides in the {@code bs} oneof) — so ALL byte[] args collapsed onto one
 * key and {@code @Cached} handed back another argument's cached bytes. These tests fail against
 * that implementation and pass against the dataCase-tagged, md5-keyed fix.
 */
class CacheKeyCollisionTest {

    /** Cheap concrete CacheManager just to reach the default {@code cacheKey}. Not exercised for storage. */
    private final CacheManager cache = new SimpleLRUCache(16);

    private static InputProto bytesInput(byte[] bs) {
        return InputProto.newBuilder().setBs(bs).build();
    }

    private static InputProto utf8Input(String s) {
        return InputProto.newBuilder().setUtf8(s).build();
    }

    @Test
    void differentByteArraysProduceDifferentKeys() {
        MethodStub stub = CacheStubs.bytesStub();
        byte[] a = "payload-alpha".getBytes(StandardCharsets.UTF_8);
        byte[] b = "payload-bravo".getBytes(StandardCharsets.UTF_8);

        String keyA = cache.cacheKey(stub, bytesInput(a));
        String keyB = cache.cacheKey(stub, bytesInput(b));

        // The core bug: these two used to be identical, so caching arg A returned it for arg B.
        assertNotEquals(keyA, keyB,
                "distinct byte[] inputs must map to distinct cache keys");
    }

    @Test
    void byteArrayAndUtf8DoNotCollideOnSameMethod() {
        // Same full method name for both stubs; only the input shape differs. A byte[] payload and
        // a utf8 payload that happen to hash/spell similarly must never land on one key.
        MethodStub bytesStub = CacheStubs.bytesStub();
        MethodStub utf8Stub = CacheStubs.utf8Stub();

        String byteKey = cache.cacheKey(bytesStub, bytesInput("hello".getBytes(StandardCharsets.UTF_8)));
        String utf8Key = cache.cacheKey(utf8Stub, utf8Input("hello"));

        assertNotEquals(byteKey, utf8Key,
                "byte[] and utf8 inputs must be tagged into different key namespaces");
        assertTrue(byteKey.contains(":b:"), () -> "byte[] key should carry the b: tag: " + byteKey);
        assertTrue(utf8Key.contains(":u:"), () -> "utf8 key should carry the u: tag: " + utf8Key);
    }

    @Test
    void identicalByteArraysProduceSameKey() {
        MethodStub stub = CacheStubs.bytesStub();
        byte[] first = {1, 2, 3, 4, 5};
        byte[] second = {1, 2, 3, 4, 5}; // equal content, distinct array instance

        String keyFirst = cache.cacheKey(stub, bytesInput(first));
        String keySecond = cache.cacheKey(stub, bytesInput(second));

        // Determinism: equal payloads must hit the same cache slot, else the cache never hits.
        assertEquals(keyFirst, keySecond,
                "equal byte[] content must produce the same cache key");
    }

    @Test
    void emptyInputYieldsNoneShape() {
        MethodStub stub = CacheStubs.bytesStub();
        InputProto empty = InputProto.newBuilder().build(); // DATA_NOT_SET, no bs, no utf8

        String key = cache.cacheKey(stub, empty);

        assertTrue(key.endsWith(":n:"), () -> "empty input should use the n: shape: " + key);
    }
}
