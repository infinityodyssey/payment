package com.yourcompany.payment.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Boot 4 ships Jackson 3, so the package is tools.jackson and java.time support
 * is built in — no JavaTimeModule registration.
 */
@Configuration
public class JacksonConfig {

    /**
     * Dedicated mapper for Redis payloads.
     *
     * Deliberately not a polymorphic-typing mapper: default typing over a store another
     * process can write to is a deserialization-gadget risk. Everything in Redis is read
     * back into a known concrete class.
     */
    @Bean("redisObjectMapper")
    public ObjectMapper redisObjectMapper() {
        return JsonMapper.builder()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
