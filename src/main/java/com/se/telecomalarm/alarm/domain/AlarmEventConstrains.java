package com.se.telecomalarm.alarm.domain;

/**
 * Length limits of the text fields, in characters. The same numbers are in the column types
 * of alarm_events (varchar(64) and varchar(1024)), so a longer value would be a database error.
 */
public final class AlarmEventConstrains {

    public static final int ID_MAX_LENGTH = 64;
    public static final int DESCRIPTION_MAX_LENGTH = 1024;

    private AlarmEventConstrains() {
    }
}
