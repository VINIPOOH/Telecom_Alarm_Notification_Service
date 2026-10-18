package com.se.telecomalarm.alarm.service.intake;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import com.se.telecomalarm.alarm.config.ElementLockProperties;
import com.se.telecomalarm.alarm.db.repository.AlarmPairLockRepository;
import org.springframework.stereotype.Service;

/**
 * The per-element JVM lock: a fixed array of {@link ReentrantLock}s ("stripes"); an element always maps to the same
 * stripe by the hash of its elementId.
 * <p>
 * It makes "transaction, commit, queue the notifications" one block per element, so the notifications of an element
 * are queued in commit order. Only inside one instance. The correctness of the state is not its job: that is the
 * advisory lock of the alarm ({@link AlarmPairLockRepository}), which holds across instances.
 * <p>
 * Two elements on one stripe just wait for each other: lost parallelism, never wrong data. A fixed array, not a map
 * per element: no growth with the number of elements and nothing to clean up.
 */
@Service
class ElementLocksService {

    private final ReentrantLock[] stripes;

    ElementLocksService(ElementLockProperties properties) {
        stripes = new ReentrantLock[properties.stripes()];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /**
     * Runs {@code action} holding the lock of the element; the lock is released even if the action throws.
     */
    <T> T callLocked(String elementId, Supplier<T> action) {
        Lock lock = stripes[stripeOf(elementId)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private int stripeOf(String elementId) {
        int hash = elementId.hashCode();
        // Mix the high bits into the low ones (as HashMap does): ids that differ only at the end ("BTS-100",
        // "BTS-101") already differ in the low bits, but without this the high bits would be wasted for any
        // stripe count that is a power of two.
        hash ^= hash >>> 16;
        return Math.floorMod(hash, stripes.length);
    }
}
