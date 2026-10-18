-- Closed value sets as PostgreSQL enum types.
-- Values are ordered as declared, so ORDER BY severity gives INFO < WARNING < MAJOR < CRITICAL.
CREATE TYPE alarm_severity AS ENUM ('INFO', 'WARNING', 'MAJOR', 'CRITICAL');
CREATE TYPE alarm_status AS ENUM ('RAISED', 'CLEARED');
-- Whether the event counts for the alarm state. It is not a final "result": a rejected CLEARED becomes accepted when
-- its earlier RAISED arrives (resurrection). Named like the Java enum AlarmEventAcceptance.
CREATE TYPE event_acceptance AS ENUM ('ACCEPTED', 'REJECTED');

-- Tables are named by what one row is, in the plural: a row of alarm_events is one received event, a row of
-- active_alarms is one active alarm.

-- Every unique received event: accepted ones and rejected CLEARED events.
CREATE TABLE alarm_events
(
    event_id          varchar(64) PRIMARY KEY,                -- idempotency: INSERT ... ON CONFLICT (event_id)
    element_id        varchar(64)      NOT NULL,
    alarm_code        varchar(64)      NOT NULL,
    severity          alarm_severity   NOT NULL,
    status            alarm_status     NOT NULL,
    event_timestamp   bigint           NOT NULL,              -- station time, epoch milliseconds as received
    description       varchar(1024),
    acceptance        event_acceptance NOT NULL,
    notification_sent boolean          NOT NULL DEFAULT false -- set to true by the notification sender after a 2xx
);

-- Indexes of GET /api/alarms/history, in its page order (event_timestamp, event_id) for keyset pagination. Each one is
-- "filter columns, then the page order", so a page is read straight from the index: no sort, only limit + 1 rows,
-- whatever the size of the history. History is read rarely and written on every event, so each index costs one more
-- write per event; they are there because the history is kept without a time limit and a busy element collects a
-- long one.

-- No filter.
CREATE INDEX idx_alarm_events_ts_id ON alarm_events (event_timestamp, event_id);

-- Filter by elementId.
CREATE INDEX idx_alarm_events_element_ts_id ON alarm_events (element_id, event_timestamp, event_id);

-- Filter by alarmCode. Without it a rare code means reading almost the whole (event_timestamp, event_id) index.
CREATE INDEX idx_alarm_events_code_ts_id ON alarm_events (alarm_code, event_timestamp, event_id);

-- Filter by both. It also serves the history of one alarm (pair) in time order: rule-2 decisions and resurrection
-- lookups (the latest accepted event, the CLEARED to resurrect).
CREATE INDEX idx_alarm_events_pair_ts_id ON alarm_events (element_id, alarm_code, event_timestamp, event_id);

-- Active alarms: one row per active pair, pointing at its latest accepted RAISED event.
-- "One active row per pair" is guaranteed by the per-pair advisory lock, not by a constraint.
CREATE TABLE active_alarms
(
    event_id varchar(64) PRIMARY KEY REFERENCES alarm_events (event_id)
);
