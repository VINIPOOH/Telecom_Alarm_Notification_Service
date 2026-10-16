package com.se.telecomalarm.api;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationTests extends ApiTestBase {

    @Test
    @DisplayName("H-01 context starts and Flyway has created the tables and enum types")
    void h01_contextStarts_andMigrationIsApplied() {
        List<String> tables = jdbc.queryForList("""
                                                        SELECT table_name
                                                        FROM information_schema.tables
                                                        WHERE table_schema = current_schema()
                                                        """, String.class);
        assertThat(tables).contains("alarm_events", "active_alarms");

        List<String> enumTypes = jdbc.queryForList("""
                                                           SELECT t.typname
                                                           FROM pg_type t
                                                           JOIN pg_namespace n ON n.oid = t.typnamespace
                                                           WHERE n.nspname = current_schema() AND t.typtype = 'e'
                                                           """, String.class);
        assertThat(enumTypes).contains("alarm_severity", "alarm_status", "event_acceptance");
    }
}
