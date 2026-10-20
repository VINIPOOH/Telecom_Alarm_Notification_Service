package com.se.telecomalarm.alarm.db.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * {@code history} is LAZY: a query that returns active rows and reads their history must {@code join fetch} it,
 * otherwise every row costs one more SELECT (N+1).
 */
@Entity
@Table(name = "active_alarms")
public class ActiveAlarmEntity {

    @Id
    @Getter
    private String eventId;

    @Getter
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private AlarmEventEntity history;

    /**
     * For Hibernate only.
     */
    protected ActiveAlarmEntity() {
    }

    public ActiveAlarmEntity(AlarmEventEntity history) {
        this.history = history;
    }
}
