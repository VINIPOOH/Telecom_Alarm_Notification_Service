package com.se.telecomalarm.alarm.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application clock.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    /**
     * The source of "now" for the whole application. Injected instead of calling System.currentTimeMillis(), so a
     * test can replace it with a fixed clock. UTC: only epoch milliseconds are read, the zone does not matter.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
