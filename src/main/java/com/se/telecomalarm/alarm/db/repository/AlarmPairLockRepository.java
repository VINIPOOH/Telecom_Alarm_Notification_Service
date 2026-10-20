package com.se.telecomalarm.alarm.db.repository;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-alarm lock: PostgreSQL's pg_advisory_xact_lock. It lives in the database, so the steps "read the alarm's
 * history, decide, write" of one alarm run one after another, even across several application instances. It is
 * released when the transaction ends (commit or rollback); there is no unlock call.
 */
@Repository
public class AlarmPairLockRepository {

    private final EntityManager entityManager;

    AlarmPairLockRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * MANDATORY: outside a transaction the lock would be taken in autocommit and released at once, silently. With
     * MANDATORY such a call fails instead.
     * <p>
     * Why "SELECT 1 FROM": the lock function returns nothing (void), and Hibernate cannot read a void result. Called
     * inside FROM, it still takes the lock, and the query returns the plain number 1, which Hibernate can read.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String elementId, String alarmCode) {
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(:elementKey, :alarmCodeKey)")
                .setParameter("elementKey", elementId.hashCode())
                .setParameter("alarmCodeKey", alarmCode.hashCode())
                .getSingleResult();
    }
}
