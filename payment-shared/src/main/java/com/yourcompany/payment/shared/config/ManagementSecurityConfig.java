package com.yourcompany.payment.shared.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Actuator chain at @Order(1), so it matches before each service's API chain ends in
 * anyRequest().denyAll() and denies every Prometheus scrape.
 *
 * Metrics are not public. Even on a separate port bound to the internal interface, an
 * unauthenticated /actuator/prometheus leaks endpoint names, traffic volumes, error
 * rates and JVM internals to anything on the pod network — in most clusters, every other
 * pod.
 *
 * Liveness and readiness stay open because the orchestrator probes them before any
 * credential exists, and they return nothing but UP or DOWN.
 */
@Configuration
@RequiredArgsConstructor
public class ManagementSecurityConfig {

    private final SharedProperties props;

    @Bean
    @Order(1)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(EndpointRequest.toAnyEndpoint())
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(EndpointRequest.to("health")).permitAll()
                .anyRequest().hasRole("ACTUATOR"))
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public UserDetailsService actuatorUserDetailsService(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User
                .withUsername(props.getSecurity().getActuatorUsername())
                .password(encoder.encode(props.getSecurity().getActuatorPassword()))
                .roles("ACTUATOR")
                .build());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
