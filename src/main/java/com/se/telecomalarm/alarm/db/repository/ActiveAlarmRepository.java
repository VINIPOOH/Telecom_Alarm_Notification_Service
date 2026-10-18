package com.se.telecomalarm.alarm.db.repository;

import java.util.List;
import java.util.Optional;
import com.se.telecomalarm.alarm.db.entity.ActiveAlarmEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ActiveAlarmRepository extends JpaRepository<ActiveAlarmEntity, String> {

    /**
     * "At most one row per alarm" is not a database constraint: it holds because every change of an alarm runs under its
     * advisory lock ({@link AlarmPairLockRepository}).
     */
    Optional<ActiveAlarmEntity> findByHistory_ElementIdAndHistory_AlarmCode(String elementId, String alarmCode);

    @EntityGraph(attributePaths = "history")
    List<ActiveAlarmEntity> findAllByOrderByHistory_ElementIdAscHistory_AlarmCodeAsc();

    @EntityGraph(attributePaths = "history")
    List<ActiveAlarmEntity> findAllByHistory_ElementIdOrderByHistory_AlarmCodeAsc(String elementId);

    @Query("""
            select h.severity, count(*) from ActiveAlarmEntity a
            join a.history h
            group by h.severity
            """)
    List<Object[]> countActiveBySeverity();
}
