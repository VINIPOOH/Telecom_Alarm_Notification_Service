package com.se.telecomalarm.alarm.db.repository;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-alarm lock: {@code pg_advisory_xact_lock(int, int)}. It lives in the database, so all decisions about one
 * alarm (read its history, decide, write) run one after another across any number of application instances.
 * Released at the end of the transaction, commit or rollback; there is no unlock.
 * <p>
 * Native SQL through the {@link EntityManager}, not a Spring Data repository: an advisory lock belongs to no table,
 * and JPA locks are row locks, while a new alarm has no row yet.
 * <p>
 * Key: {@code elementId.hashCode()} and {@code alarmCode.hashCode()}. {@link String#hashCode} is fixed by the Java
 * specification, so an alarm gets the same key in every instance. Two alarms share a key only if both hashes collide,
 * and that costs only an extra wait, never wrong data. The strings are not concatenated: ("BTS-1", "00X") and
 * ("BTS-100", "X") would give the same string.
 */
@Repository
public class AlarmPairLockRepository {

    private final EntityManager entityManager;

    AlarmPairLockRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * {@link Propagation#MANDATORY}: outside a transaction the statement would run in autocommit, and the lock would be
     * released at once without any error. Now it will throw IllegalTransactionStateException.
     * {@code SELECT 1 FROM}: the function returns {@code void}, which has no Java mapping; called in FROM it gives one
     * row. The keys are {@code int}, so PostgreSQL picks the (integer, integer) form.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String elementId, String alarmCode) {
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(:elementKey, :alarmCodeKey)")
                .setParameter("elementKey", elementId.hashCode())
                .setParameter("alarmCodeKey", alarmCode.hashCode())
                .getSingleResult();
    }
}
