package com.yourcompany.payment.shared.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Import this from each service's application class so the shared beans are picked up.
 *
 * Deliberately explicit rather than a Spring Boot auto-configuration: a service should
 * declare that it depends on the shared contract, not acquire it invisibly from the
 * classpath.
 */
@Configuration
@ComponentScan(basePackages = "com.yourcompany.payment.shared")
@EnableConfigurationProperties(SharedProperties.class)
public class SharedConfig {
}
