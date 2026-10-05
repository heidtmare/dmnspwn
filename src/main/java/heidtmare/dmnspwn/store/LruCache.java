package heidtmare.dmnspwn.store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** A thread-safe map that forgets its least recently used entries beyond a fixed size. */
public final class LruCache<K, V> {

    private final Map<K, V> map;

    public LruCache(int maxEntries) {
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public synchronized V get(K key) {
        return map.get(key);
    }

    public synchronized void put(K key, V value) {
        map.put(key, value);
    }

    public synchronized void remove(K key) {
        map.remove(key);
    }

    public synchronized void removeIf(Predicate<K> key) {
        map.keySet().removeIf(key);
    }

    public synchronized int size() {
        return map.size();
    }
}
