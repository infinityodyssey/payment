package com.yourcompany.payment.fetch.config;

import com.yourcompany.payment.shared.security.AntiReplayFilter;
import com.yourcompany.payment.shared.security.SessionContextFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stops the shared filters running twice.
 *
 * A filter annotated @Component is picked up by servlet auto-registration AND inserted
 * into the Spring Security chain. Without these the anti-replay filter runs first,
 * consumes the nonce, and the second execution inside the chain sees a duplicate and
 * rejects every request.
 *
 * Only AntiReplayFilter and SessionContextFilter need this. The two token filters are
 * plain beans declared in SecurityConfig precisely so they never auto-register.
 *
 * Every service that uses the shared @Component filters needs this class.
 */
@Configuration
public class FilterRegistrationConfig {

    @Bean
    public FilterRegistrationBean<AntiReplayFilter> disableAntiReplayAutoRegistration(AntiReplayFilter f) {
        FilterRegistrationBean<AntiReplayFilter> r = new FilterRegistrationBean<>(f);
        r.setEnabled(false);
        return r;
    }

    @Bean
    public FilterRegistrationBean<SessionContextFilter> disableSessionAutoRegistration(SessionContextFilter f) {
        FilterRegistrationBean<SessionContextFilter> r = new FilterRegistrationBean<>(f);
        r.setEnabled(false);
        return r;
    }
}
