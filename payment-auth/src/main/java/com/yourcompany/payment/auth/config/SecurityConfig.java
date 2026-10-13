package com.yourcompany.payment.auth.config;

import com.yourcompany.payment.shared.security.AntiReplayFilter;
import com.yourcompany.payment.shared.security.SessionContextFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * API chain, ordered after the shared actuator chain so Prometheus scrapes are matched
 * by their own rules rather than by anyRequest().denyAll() here.
 *
 * Spring Security 7 removes AntPathRequestMatcher and MvcRequestMatcher, so all matching
 * goes through PathPatternRequestMatcher. PathPatternParser also rejects mid-pattern
 * wildcards; every pattern here is a trailing match.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final SessionContextFilter sessionContextFilter;
    private final AntiReplayFilter antiReplayFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder m = PathPatternRequestMatcher.withDefaults();

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))

            // Spring's CSRF token repository does not fit a cross-origin SPA. The
            // replacement is the cookie/header double-submit in SessionContextFilter,
            // plus SameSite=Strict and a CORS policy that will not preflight an unknown
            // origin.
            .csrf(AbstractHttpConfigurer::disable)

            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .anonymous(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)

            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true).preload(true).maxAgeInSeconds(63072000))
                .cacheControl(cache -> {}))

            .exceptionHandling(e -> e
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

            .authorizeHttpRequests(auth -> auth
                // The only unauthenticated browser endpoint: it establishes the session.
                .requestMatchers(m.matcher(HttpMethod.POST, "/api/v1/auth/handshake")).permitAll()
                // Partner credentials are checked in the service, not by the filter chain.
                .requestMatchers(m.matcher(HttpMethod.POST, "/api/v1/partner/token")).permitAll()
                .requestMatchers(m.matcher("/api/v1/auth/**")).hasAuthority("ROLE_SESSION")
                .anyRequest().denyAll())

            .addFilterBefore(antiReplayFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(sessionContextFilter, AntiReplayFilter.class);

        return http.build();
    }
}
