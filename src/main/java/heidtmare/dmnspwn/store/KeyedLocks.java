package heidtmare.dmnspwn.store;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A fixed set of locks shared by all keys (lock striping), so that locking per model id needs no memory per id.
 * Unrelated keys may share a lock, which only costs some concurrency.
 */
final class KeyedLocks {

    private static final int STRIPES = 64;

    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    KeyedLocks() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    <T> T locked(String key, Supplier<T> action) {
        ReentrantLock lock = locks[Math.floorMod(key.hashCode(), STRIPES)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
