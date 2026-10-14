package com.yourcompany.payment.auth.config;

import com.yourcompany.payment.shared.config.LmsRestClientConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The SMS gateway client. The LMS client comes from
 * {@link com.yourcompany.payment.shared.config.LmsRestClientConfig}, which every service
 * that calls the LMS inherits.
 *
 * The bean name is part of the contract: {@code smsRestClient} matches the upstream name
 * {@code sms} that {@link com.yourcompany.payment.auth.client.SmsApiClient} passes, and
 * therefore the {@code sms} bulkhead and circuit breaker. Separate instances from the
 * LMS's on purpose - a degraded LMS must not consume the capacity that dispatches
 * one-time codes.
 */
@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final AuthProperties auth;

    @Bean("smsRestClient")
    public RestClient smsRestClient() {
        return LmsRestClientConfig.restClient(auth.getSms().getTimeoutSeconds());
    }
}
