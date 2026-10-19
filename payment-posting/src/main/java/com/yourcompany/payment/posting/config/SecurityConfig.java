package com.yourcompany.payment.posting.config;

import com.yourcompany.payment.shared.security.JwtService;
import com.yourcompany.payment.shared.security.PartnerAuthFilter;
import com.yourcompany.payment.shared.security.Role;
import com.yourcompany.payment.shared.web.ApiErrorWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Partner authorisation, done by the filter chain from the role inside the JWT.
 *
 * This is what "the role lives in the token" buys. One HMAC verification in
 * PartnerAuthFilter turns the token's roles into Spring Security authorities, and the
 * matchers below decide access. No Redis, no user-table read, no per-controller check
 * anyone has to remember to write.
 *
 * The vendor callback is the exception and does NOT belong here. It is authenticated by
 * the gateway's signature or checksum, not by a token we issued, because the vendor
 * never asked us for one. Mixing the two would mean a partner token could post a vendor
 * callback.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;
    private final ApiErrorWriter errorWriter;

    /** A bean, not a @Component, so it runs only where this config puts it. */
    @Bean
    public PartnerAuthFilter partnerAuthFilter() {
        return new PartnerAuthFilter(jwtService, errorWriter);
    }

    @Bean
    @Order(2)
    public SecurityFilterChain partnerFilterChain(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder m = PathPatternRequestMatcher.withDefaults();

        http
            .securityMatcher(m.matcher("/api/v1/partner/**"))
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .anonymous(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .exceptionHandling(e -> e
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

            .authorizeHttpRequests(auth -> auth
                .requestMatchers(m.matcher("/api/v1/partner/**"))
                        .hasAnyAuthority(Role.VENDOR, Role.ADMIN)
                // Deny by default. A new path is unreachable until someone decides which
                // role it needs, which is the correct way round.
                .anyRequest().denyAll())

            .addFilterBefore(partnerAuthFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Everything that is not a partner path. The vendor callback authenticates itself by
     * signature inside the handler; it is listed here so the chain does not deny it, not
     * because it is unauthenticated.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder m = PathPatternRequestMatcher.withDefaults();

        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .anonymous(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(m.matcher("/api/v1/payment/webhook/**")).permitAll()
                .anyRequest().denyAll());

        return http.build();
    }
}
