package com.bt.rpc.client;

import lombok.AllArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 2020-08-25 16:45
 *
 * @author Martin.C
 */
public class SimpleLRUCache implements CacheManager {


    @AllArgsConstructor
    final class ValueWrap{
        final String val;
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

    @Override
    public String get(String cacheKey) {
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
    public void set(String cacheKey, String bytesStr, int expireSeconds) {
        var wrap = new ValueWrap(bytesStr,System.currentTimeMillis() + expireSeconds* 1000L);
        map.put(cacheKey,wrap);
    }
}
