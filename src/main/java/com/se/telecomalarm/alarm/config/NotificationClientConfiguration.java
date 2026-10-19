package com.se.telecomalarm.alarm.config;

import java.net.http.HttpClient;
import com.se.telecomalarm.alarm.service.notification.NotificationClient;
import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = NotificationClient.GROUP, types = NotificationClient.class)
class NotificationClientConfiguration {

    /**
     * Restricts the JDK HttpClient to HTTP/1.1 (by default it tries HTTP/2).
     * Applies to every HTTP client Spring Boot builds with the JDK factory, not only to {@link NotificationClient};
     */
    @Bean
    ClientHttpRequestFactoryBuilderCustomizer<JdkClientHttpRequestFactoryBuilder> http11OnlyJdkClient() {
        return builder -> builder.withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1));
    }
}
