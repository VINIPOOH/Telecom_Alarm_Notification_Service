package com.se.telecomalarm.alarm.db.repository;

import java.util.List;
import com.se.telecomalarm.alarm.db.entity.AlarmEventEntity;
import com.se.telecomalarm.alarm.domain.AlarmEventAcceptance;
import com.se.telecomalarm.alarm.domain.AlarmStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enum values in the JPQL are written as strings ('ACCEPTED'), not as Java enum constants: Hibernate renders a Java
 * enum constant as a cast to a type named after the Java class, which PostgreSQL does not have.
 */
public interface AlarmEventRepository extends JpaRepository<AlarmEventEntity, String> {

    @Modifying
    @Query(value = """
            INSERT INTO alarm_events
            	(event_id, element_id, alarm_code, severity, status, event_timestamp, description, acceptance)
            VALUES
            	(:eventId, :elementId, :alarmCode,
            	 CAST(:severity AS alarm_severity), CAST(:status AS alarm_status),
            	 :eventTimestamp, CAST(:description AS varchar), CAST(:acceptance AS event_acceptance))
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertRowIfAbsent(
            @Param("eventId") String eventId,
            @Param("elementId") String elementId,
            @Param("alarmCode") String alarmCode,
            @Param("severity") String severity,
            @Param("status") String status,
            @Param("eventTimestamp") long eventTimestamp,
            @Param("description") String description,
            @Param("acceptance") String acceptance);

    @Transactional
    @Modifying
    @Query("update AlarmEventEntity h set h.notificationSent = true where h.eventId = :eventId")
    int markNotificationSent(@Param("eventId") String eventId);

    /**
     * Several only when timestamps are equal. The caller picks one with AlarmEventComparator: its last tie-break compares eventId strings,
     * and string order in SQL depends on the database collation.
     */
    @Query("""
            select h from AlarmEventEntity h
            where h.elementId = :elementId
              and h.alarmCode = :alarmCode
              and h.acceptance = 'ACCEPTED'
              and h.eventTimestamp = (
                  select max(h2.eventTimestamp) from AlarmEventEntity h2
                  where h2.elementId = :elementId
                    and h2.alarmCode = :alarmCode
                    and h2.acceptance = 'ACCEPTED')
            """)
    List<AlarmEventEntity> findAcceptedByAlarmAtLatestTimestamp(
            @Param("elementId") String elementId,
            @Param("alarmCode") String alarmCode);

    default boolean hasAcceptedRaisedAlarmBefore(String elementId, String alarmCode, long clearedTimestamp) {
        return existsByElementIdAndAlarmCodeAndStatusAndAcceptanceAndEventTimestampLessThan(
                elementId, alarmCode, AlarmStatus.RAISED, AlarmEventAcceptance.ACCEPTED, clearedTimestamp);
    }

    boolean existsByElementIdAndAlarmCodeAndStatusAndAcceptanceAndEventTimestampLessThan(
            String elementId,
            String alarmCode,
            AlarmStatus status,
            AlarmEventAcceptance acceptance,
            long eventTimestamp);

    /**
     * Resurrection in one statement: accepts the rejected CLEARED events after the given RAISED and returns them, for
     * the notifications. Native, because RETURNING is PostgreSQL, not JPQL. No @Modifying on purpose: with it Spring
     * Data runs executeUpdate and returns only the row count.
     */
    @Query(value = """
            UPDATE alarm_events
            SET acceptance = 'ACCEPTED'
            WHERE element_id = :elementId
              AND alarm_code = :alarmCode
              AND status = 'CLEARED'
              AND acceptance = 'REJECTED'
              AND event_timestamp > :raisedTimestamp
            RETURNING *
            """, nativeQuery = true)
    List<AlarmEventEntity> acceptRejectedClearedAlarmsAfter(
            @Param("elementId") String elementId,
            @Param("alarmCode") String alarmCode,
            @Param("raisedTimestamp") long raisedTimestamp);

    /**
     * A null filter switches that filter off. The order is total because event_id is unique. The caller asks for one
     * row more than the page size, to know whether there is a next page.
     */
    @Query("""
            select h from AlarmEventEntity h
            where h.acceptance = 'ACCEPTED'
              and (:elementId is null or h.elementId = :elementId)
              and (:alarmCode is null or h.alarmCode = :alarmCode)
            order by h.eventTimestamp, h.eventId
            """)
    List<AlarmEventEntity> findAcceptedFirstPage(
            @Param("elementId") String elementId,
            @Param("alarmCode") String alarmCode,
            Limit limit);

    /**
     * Like findAcceptedFirstPage, but after the given position (keyset pagination over the index on the same columns).
     */
    @Query("""
            select h from AlarmEventEntity h
            where h.acceptance = 'ACCEPTED'
              and (:elementId is null or h.elementId = :elementId)
              and (:alarmCode is null or h.alarmCode = :alarmCode)
              and (h.eventTimestamp, h.eventId) > (:afterTimestamp, :afterEventId)
            order by h.eventTimestamp, h.eventId
            """)
    List<AlarmEventEntity> findAcceptedNextPage(
            @Param("elementId") String elementId,
            @Param("alarmCode") String alarmCode,
            @Param("afterTimestamp") long afterTimestamp,
            @Param("afterEventId") String afterEventId,
            Limit limit);
}
