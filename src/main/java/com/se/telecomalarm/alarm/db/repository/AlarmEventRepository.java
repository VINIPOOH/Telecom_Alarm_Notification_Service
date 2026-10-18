package com.se.telecomalarm.alarm.db.repository;

import java.util.List;
import com.se.telecomalarm.alarm.db.entity.AlarmEventEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixed enum values are written into the JPQL as string literals ({@code 'ACCEPTED'}): Hibernate sends them without a
 * cast and PostgreSQL resolves them to the enum type of the column. A Java enum literal
 * ({@code AlarmEventAcceptance.ACCEPTED}) does not work: Hibernate renders it as a cast to a type named after the Java
 * class, which the database does not have (checked 18.10.2026 on Hibernate 7.4). A misspelt value is rejected by
 * PostgreSQL when the query runs ("invalid input value for enum"), so the tests catch it.
 */
public interface AlarmEventRepository extends JpaRepository<AlarmEventEntity, String> {

    /**
     * @return 1 if the row was inserted, 0 if the event_id was already taken
     * <p>
     * The transaction stays usable to read the stored row.
     * JPA cannot do that: save() is merge, which can silently overwrite the stored row; persist
     * fails with a unique violation, which aborts the PostgreSQL transaction.
     * Enums are cast explicitly: PostgreSQL does not convert a varchar parameter to an enum type by itself.
     */
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

    /**
     * @return the number of rows changed: 1
     */
    @Transactional
    @Modifying
    @Query("update AlarmEventEntity h set h.notificationSent = true where h.eventId = :eventId")
    int markNotificationSent(@Param("eventId") String eventId);

    /**
     * The accepted events of the alarm (elementId, alarmCode) at its greatest timestamp: several rows only on equal
     * timestamps. The caller breaks the ties by the total order ({@code AlarmEventComparator}); the eventId tie-break
     * cannot be done in SQL, whose string order follows the database collation.
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

    /**
     * Whether the alarm has an accepted RAISED earlier than a CLEARED at {@code clearedTimestamp}. Between a RAISED and
     * a CLEARED the total order is decided by the timestamp alone (on equal timestamps RAISED is later), so a strict
     * "<" is the whole rule.
     */
    @Query("""
            select case when count(h) > 0 then true else false end
            from AlarmEventEntity h
            where h.elementId = :elementId
              and h.alarmCode = :alarmCode
              and h.status = 'RAISED'
              and h.acceptance = 'ACCEPTED'
              and h.eventTimestamp < :clearedTimestamp
            """)
    boolean hasAcceptedRaisedAlarmBefore(
            @Param("elementId") String elementId,
            @Param("alarmCode") String alarmCode,
            @Param("clearedTimestamp") long clearedTimestamp);

    /**
     * The rejected CLEARED events of the alarm later than {@code raisedTimestamp} (strictly greater timestamp): the
     * candidates for resurrection. The only read of REJECTED rows.
     */
    @Query("""
            select h from AlarmEventEntity h
            where h.elementId = :elementId
              and h.alarmCode = :alarmCode
              and h.status = 'CLEARED'
              and h.acceptance = 'REJECTED'
              and h.eventTimestamp > :raisedTimestamp
            """)
    List<AlarmEventEntity> findRejectedClearedAlarmsAfterGivenAlarm(
            @Param("elementId") String elementId,
            @Param("alarmCode") String alarmCode,
            @Param("raisedTimestamp") long raisedTimestamp);

    /**
     * The first page of GET /api/alarms/history: accepted events in history order (event_timestamp, event_id: total,
     * since event_id is unique). A null filter parameter switches that filter off. The caller asks for one row more
     * than the page size to know whether a next page exists.
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
     * The page after the position (afterTimestamp, afterEventId): {@link #findAcceptedFirstPage} plus a keyset
     * condition, a comparison of tuples that PostgreSQL can answer from the index (event_timestamp, event_id).
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
