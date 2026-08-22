package com.e2eechat.core.keys;

import javax.crypto.SecretKey;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SessionKeyCache {
    private final ConcurrentHashMap<String, SecretKey> cache = new ConcurrentHashMap<>();

    public void put(String peerId, SecretKey key) {
        cache.put(peerId, key);
    }

    public Optional<SecretKey> get(String peerId) {
        return Optional.ofNullable(cache.get(peerId));
    }

    public void remove(String peerId) {
        cache.remove(peerId);
    }

    public void clear() {
        cache.clear();
    }
}
