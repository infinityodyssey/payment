package com.yourcompany.payment.auth.config;

import com.yourcompany.payment.shared.security.AntiReplayFilter;
import com.yourcompany.payment.shared.security.PartnerAuthFilter;
import com.yourcompany.payment.shared.security.SessionContextFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stops the security filters running twice.
 *
 * A filter annotated @Component is picked up by servlet auto-registration AND inserted
 * into the Spring Security chain. Without these overrides the anti-replay filter runs
 * first, consumes the nonce, and the second execution inside the chain sees a duplicate
 * and rejects every single request.
 *
 * Every service that uses the shared filters needs this class.
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

    /**
     * Without this the partner filter would run on EVERY request, including
     * /api/v1/partner/token itself — which has no bearer token yet, so no partner could
     * ever obtain one. It belongs only on the chains that put it there explicitly.
     */
    @Bean
    public FilterRegistrationBean<PartnerAuthFilter> disablePartnerAuthAutoRegistration(PartnerAuthFilter f) {
        FilterRegistrationBean<PartnerAuthFilter> r = new FilterRegistrationBean<>(f);
        r.setEnabled(false);
        return r;
    }
}
