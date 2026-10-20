package com.se.telecomalarm.alarm.service.intake;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import com.se.telecomalarm.alarm.config.ElementLockProperties;
import com.se.telecomalarm.alarm.db.repository.AlarmPairLockRepository;
import org.springframework.stereotype.Service;

/**
 * The per-element lock, in the JVM. There is a fixed number of ReentrantLocks (alarm.element-locks.count), and every
 * elementId always gets the same one of them, chosen by the hash of the elementId. Several elements may share a lock.
 * <p>
 * Its purpose is the notification order: "transaction, commit, queue the notifications" runs as one block per
 * element, so an element's notifications are queued in commit order. It works inside one instance only. The state
 * does not depend on it: that is the per-alarm advisory lock ({@link AlarmPairLockRepository}), which works across
 * instances.
 * <p>
 * Two elements that share a lock only wait for each other: less parallelism, never wrong data. A fixed number of
 * locks rather than one lock per element: it does not grow with the number of elements, and there is nothing to
 * clean up.
 */
@Service
class ElementLocksService {

    private final ReentrantLock[] stripes;

    ElementLocksService(ElementLockProperties properties) {
        stripes = new ReentrantLock[properties.count()];
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
