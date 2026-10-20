package com.se.telecomalarm.alarm.domain;

//Values and their order match the PostgreSQL type alarm_severity.
public enum AlarmSeverity {
    INFO,
    WARNING,
    MAJOR,
    CRITICAL
}
