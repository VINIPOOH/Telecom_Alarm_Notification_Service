package com.se.telecomalarm.alarm.config;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A separate JsonMapper for the history cursor (the after / next values of the history API).
 * <p>
 * Separate because the cursor needs other rules than the API: a cursor with unknown fields is not ours and must be
 * rejected, and the cursor format must not change when someone changes the Jackson settings of the API.
 * <p>
 * It is not the default JsonMapper (defaultCandidate = false), so the rest of the application keeps Spring Boot's
 * mapper. To get this one, inject it by its qualifier.
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
