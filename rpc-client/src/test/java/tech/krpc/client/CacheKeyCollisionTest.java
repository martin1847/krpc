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
        // Toothless-proofing (fix round 1): use ONE stub so the full method name is identical, and
        // pick the utf8 payload == md5(bytePayload) so the b:/u: value-parts are byte-for-byte equal
        // (md5 is 32 hex chars, below KEY_MAX_SIZE_UNDIGEST, so the utf8 branch stores it verbatim).
        // The ONLY thing separating the two keys is then the dataCase tag — a mutation that drops the
        // tag collapses them onto one key and assertNotEquals goes red. Previously the two stubs had
        // different method names, so the keys differed for that reason alone and the tag was untested.
        MethodStub stub = CacheStubs.bytesStub();
        byte[] payload = "collision-probe".getBytes(StandardCharsets.UTF_8);
        String sameValuePart = SimpleMD5.md5(payload); // == what the b: branch emits for `payload`

        String byteKey = cache.cacheKey(stub, bytesInput(payload));
        String utf8Key = cache.cacheKey(stub, utf8Input(sameValuePart));

        assertNotEquals(byteKey, utf8Key,
                "same method + identical value-part: only the dataCase tag may separate the keys");
        assertEquals(stub.methodDescriptor.getFullMethodName() + ":b:" + sameValuePart, byteKey,
                "byte[] key must be method:b:<md5>");
        assertEquals(stub.methodDescriptor.getFullMethodName() + ":u:" + sameValuePart, utf8Key,
                "utf8 key must be method:u:<value>");
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
