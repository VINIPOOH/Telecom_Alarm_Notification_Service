package com.se.telecomalarm.alarm.db.repository;

import com.se.telecomalarm.api.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AlarmPairLockRepository#lock} against a real PostgreSQL: outside a transaction it must fail
 * ({@link Propagation#MANDATORY}) instead of taking a lock that autocommit would release at once.
 * <p>
 * {@code @DataJpaTest} runs every test method in its own transaction, and with it {@code lock} would always pass.
 * NOT_SUPPORTED switches that off, so each test decides itself whether a transaction is open.
 */
@DataJpaTest
@Import({ TestcontainersConfiguration.class, AlarmPairLockRepository.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlarmPairLockRepositoryTests {

    @Autowired
    private AlarmPairLockRepository alarmPairLockRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("lock outside a transaction throws IllegalTransactionStateException")
    void lock_outsideTransaction_throws() {
        assertThatThrownBy(() -> alarmPairLockRepository.lock("BTS-1", "LINK_DOWN"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("lock inside a transaction takes the lock without an error")
    void lock_insideTransaction_succeeds() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatCode(() -> transaction.executeWithoutResult(
                status -> alarmPairLockRepository.lock("BTS-1", "LINK_DOWN")))
                .doesNotThrowAnyException();
    }
}
