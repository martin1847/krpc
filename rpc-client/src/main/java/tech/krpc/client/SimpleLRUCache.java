package tech.krpc.client;

import tech.krpc.annotation.Doc;
import lombok.AllArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 2020-08-25 16:45
 *
 * @author Martin.C
 */
@Doc("客户端LRU缓存，不指定的话默认会使用")
public class SimpleLRUCache implements CacheManager {


    @AllArgsConstructor
    final class ValueWrap{
        final byte[] val;
        final long timestamp;
    }

    final Map<String,ValueWrap> map;

    public SimpleLRUCache() {
        this(1000);
    }

    public SimpleLRUCache(int cacheSize) {
        map = new LinkedHashMap<>(16,
                0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ValueWrap> eldest) {
                return size() > cacheSize;
            }
        };
    }

    // C1 (HARDEN-B2): accessOrder=true makes get() a STRUCTURAL mutation (moves the entry to
    // the tail). Under virtual-thread concurrency, an unsynchronized get() corrupted the linked
    // list (dirty reads, and once a 100% CPU self-spin in a broken next-pointer cycle). get()
    // and set() must share one monitor; the check-timestamp-then-remove compound below is one
    // critical section, not two.
    @Override
    public synchronized byte[] get(String cacheKey) {
        var wrap = map.get(cacheKey);
        if (wrap != null) {
            if (wrap.timestamp >= System.currentTimeMillis()) {
                return wrap.val;
            }
            map.remove(cacheKey);
        }
        return null;
    }

    @Override
    public synchronized void set(String cacheKey, byte[] bytes, int expireSeconds) {
        var wrap = new ValueWrap(bytes,System.currentTimeMillis() + expireSeconds* 1000L);
        map.put(cacheKey,wrap);
    }
}
