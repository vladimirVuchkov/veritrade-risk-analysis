package com.veritrade.reporting.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReportingLimits.class)
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
