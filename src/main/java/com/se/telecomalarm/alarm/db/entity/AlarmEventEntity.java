package com.se.telecomalarm.alarm.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import com.se.telecomalarm.alarm.db.repository.AlarmEventRepository;
import com.se.telecomalarm.alarm.domain.AlarmEventAcceptance;
import com.se.telecomalarm.alarm.domain.AlarmSeverity;
import com.se.telecomalarm.alarm.domain.AlarmStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A row of {@code alarm_events}: every unique received event, accepted or rejected.
 * Never saved through Spring Data: a new row is written only by {@link AlarmEventRepository#insertRowIfAbsent}
 */
@Entity
@Table(name = "alarm_events")
public class AlarmEventEntity {

    @Getter
    @Id
    @Column(name = "event_id")
    private String eventId;

    @Getter
    @Column(name = "element_id", nullable = false)
    private String elementId;

    @Getter
    @Column(name = "alarm_code", nullable = false)
    private String alarmCode;

    @Getter
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "severity", nullable = false)
    private AlarmSeverity severity;

    @Getter
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private AlarmStatus status;

    @Getter
    @Column(name = "event_timestamp", nullable = false)
    private long eventTimestamp;

    @Getter
    @Column(name = "description")
    private String description;

    @Setter
    @Getter
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "acceptance", nullable = false)
    private AlarmEventAcceptance acceptance;

    @Column(name = "notification_sent", nullable = false)
    private boolean notificationSent;

    /**
     * For Hibernate only: rows are created by the native insert, never with {@code new}.
     */
    protected AlarmEventEntity() {
    }

}
