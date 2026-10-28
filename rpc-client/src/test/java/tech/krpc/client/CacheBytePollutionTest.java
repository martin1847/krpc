package tech.krpc.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import tech.krpc.common.MethodStub;
import tech.krpc.internal.OutputProto;

/**
 * O10 (HARDEN-B2): the byte[] cache path must clone in BOTH directions so a caller can never
 * poison the shared cache through the array it stored or the array it read back.
 *
 * <p>Pre-fix {@code set} stored {@code message.getBs()} by reference and {@code get} handed the
 * stored array back by reference — so mutating either the source (after set) or the returned array
 * (after get) silently rewrote the cached value seen by every other caller. These tests fail
 * against that implementation and pass against the clone-on-set / clone-on-get fix.
 */
class CacheBytePollutionTest {

    private static final String KEY = "poison-key";

    /** Returned array is mutated by this caller; a later read must still see the original bytes. */
    @Test
    void mutatingReturnedArrayDoesNotPoisonCache() {
        SimpleLRUCache cache = new SimpleLRUCache(16);
        MethodStub stub = CacheStubs.bytesStub();
        byte[] original = {10, 20, 30, 40, 50};

        cache.set(stub, KEY, OutputProto.newBuilder().setC(0).setBs(original).build());

        OutputProto firstRead = cache.get(stub, KEY);
        assertNotNull(firstRead, "entry must be present with positive expireSeconds");
        byte[] firstBytes = firstRead.getBs();
        // Attacker/naive caller scribbles all over the array it was handed.
        java.util.Arrays.fill(firstBytes, (byte) 0x7F);

        OutputProto secondRead = cache.get(stub, KEY);
        assertNotNull(secondRead, "entry must still be present");
        assertArrayEquals(new byte[]{10, 20, 30, 40, 50}, secondRead.getBs(),
                "cache must be unaffected by mutation of a previously returned array");
    }

    /** Source array is mutated after set; the cached value must be a snapshot, not a live view. */
    @Test
    void mutatingSourceArrayAfterSetDoesNotChangeCachedValue() {
        SimpleLRUCache cache = new SimpleLRUCache(16);
        MethodStub stub = CacheStubs.bytesStub();
        byte[] source = {1, 2, 3, 4, 5};

        cache.set(stub, KEY, OutputProto.newBuilder().setC(0).setBs(source).build());
        // Caller reuses/mutates the buffer it just handed off.
        java.util.Arrays.fill(source, (byte) 0x00);

        OutputProto read = cache.get(stub, KEY);
        assertNotNull(read, "entry must be present");
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, read.getBs(),
                "cache must snapshot the bytes at set time, not alias the caller's buffer");
    }

    /** Two independent reads must not share a mutable array (get must clone every time). */
    @Test
    void separateReadsReturnIndependentArrays() {
        SimpleLRUCache cache = new SimpleLRUCache(16);
        MethodStub stub = CacheStubs.bytesStub();
        byte[] original = {9, 8, 7, 6};

        cache.set(stub, KEY, OutputProto.newBuilder().setC(0).setBs(original).build());

        byte[] readA = cache.get(stub, KEY).getBs();
        java.util.Arrays.fill(readA, (byte) 0x01);
        byte[] readB = cache.get(stub, KEY).getBs();

        assertArrayEquals(new byte[]{9, 8, 7, 6}, readB,
                "each read must yield a fresh clone unaffected by mutation of a prior read");
    }

    // --- Raw SPI (fix round 1) -------------------------------------------------------------------
    // The tests above exercise the CacheManager helper defaults, which cloned even before fix round 1.
    // The blocking O10 fix sank the clone INTO SimpleLRUCache's raw byte[] API (set(String,byte[],int)
    // / get(String)), reachable directly by a user-held SimpleLRUCache. These call that API with no
    // helper in the path, so a mutation removing the impl-layer clone (while keeping the helper clone)
    // still goes red.

    @Test
    void rawApiMutatingReturnedArrayDoesNotPoisonCache() {
        SimpleLRUCache cache = new SimpleLRUCache(16);
        cache.set(KEY, new byte[]{10, 20, 30, 40, 50}, 60);

        byte[] first = cache.get(KEY);
        assertNotNull(first, "entry must be present with positive expireSeconds");
        java.util.Arrays.fill(first, (byte) 0x7F);

        assertArrayEquals(new byte[]{10, 20, 30, 40, 50}, cache.get(KEY),
                "raw get() must clone: mutating a returned array cannot poison the entry");
    }

    @Test
    void rawApiMutatingSourceArrayAfterSetDoesNotChangeCachedValue() {
        SimpleLRUCache cache = new SimpleLRUCache(16);
        byte[] source = {1, 2, 3, 4, 5};
        cache.set(KEY, source, 60);
        java.util.Arrays.fill(source, (byte) 0x00);

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, cache.get(KEY),
                "raw set() must clone: mutating the source buffer after set cannot change the entry");
    }

    @Test
    void rawApiSeparateReadsReturnIndependentArrays() {
        SimpleLRUCache cache = new SimpleLRUCache(16);
        cache.set(KEY, new byte[]{9, 8, 7, 6}, 60);

        byte[] readA = cache.get(KEY);
        java.util.Arrays.fill(readA, (byte) 0x01);

        assertArrayEquals(new byte[]{9, 8, 7, 6}, cache.get(KEY),
                "each raw get() must yield a fresh clone, unaffected by mutation of a prior read");
    }
}
