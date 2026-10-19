package com.se.telecomalarm.alarm.config;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A separate, strict JsonMapper for the history cursor ({@code after} / {@code next} of GET /api/alarms/history).
 * <p>
 * Not the API mapper, for two reasons: the API ignores unknown fields, while a cursor with any field besides
 * {@code ts} and {@code id} is not ours and must be a 400; and the format of cursors already given out must not change
 * when someone edits {@code spring.jackson.*}.
 * <p>
 * {@code defaultCandidate = false}: without it a second JsonMapper bean makes plain {@code JsonMapper} injection
 * ambiguous, and Boot's API mapper ({@code @ConditionalOnMissingBean}) backs off, leaving the API on this strict one.
 * Inject it only with {@code @Qualifier(HISTORY_CURSOR_JSON_MAPPER)}.
 */
@Configuration(proxyBeanMethods = false)
public class HistoryCursorJsonConfiguration {

    public static final String HISTORY_CURSOR_JSON_MAPPER_QUALIFIER = "historyCursorJsonMapper";

    @Bean(name = HISTORY_CURSOR_JSON_MAPPER_QUALIFIER, defaultCandidate = false)
    @Qualifier(HISTORY_CURSOR_JSON_MAPPER_QUALIFIER)
    JsonMapper historyCursorJsonMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
