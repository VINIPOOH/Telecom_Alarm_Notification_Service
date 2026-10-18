package com.se.telecomalarm.alarm.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the settings of the alarm package ({@code alarm.*} in application.yaml) as beans. Each record is
 * checked at start by Bean Validation ({@code @Validated} on the record).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ AlarmIngestionProperties.class, ElementLockProperties.class, NotificationProperties.class })
class PropertiesConfiguration {
}
