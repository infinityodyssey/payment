package com.yourcompany.payment.fetch.config;

import com.yourcompany.payment.shared.security.AntiReplayFilter;
import com.yourcompany.payment.shared.security.BorrowerAuthFilter;
import com.yourcompany.payment.shared.security.JwtService;
import com.yourcompany.payment.shared.security.PartnerAuthFilter;
import com.yourcompany.payment.shared.security.Role;
import com.yourcompany.payment.shared.security.SessionContextFilter;
import com.yourcompany.payment.shared.web.ApiErrorWriter;
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
 * Two chains, because there are two trust models and a chain is selected by path.
 *
 *   @Order(1)  the shared actuator chain, from ManagementSecurityConfig
 *   @Order(2)  /api/v1/partner/**  bearer JWT, role-matched, no session, no encryption
 *   @Order(3)  /api/v1/payment/**  session cookie + header, nonce, encrypted body,
 *                                  borrower JWT verified in the service against both
 *                                  the session and the agreement
 *
 * The partner chain is ordered FIRST. If the browser chain matched /api/** first it
 * would apply the session filter to partner traffic, which carries no cookie, and every
 * partner request would fail with a session error. Ordering is the whole mechanism here,
 * so changing these numbers changes who can reach what.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final SessionContextFilter sessionContextFilter;
    private final AntiReplayFilter antiReplayFilter;
    private final CorsConfigurationSource corsConfigurationSource;
    private final JwtService jwtService;
    private final ApiErrorWriter errorWriter;

    /**
     * The two token filters are beans here rather than @Component in payment-shared, so
     * a service declares the ones it needs instead of switching off the ones it does not.
     */
    @Bean
    public PartnerAuthFilter partnerAuthFilter() {
        return new PartnerAuthFilter(jwtService, errorWriter);
    }

    @Bean
    public BorrowerAuthFilter borrowerAuthFilter() {
        return new BorrowerAuthFilter(jwtService, errorWriter);
    }

    @Bean
    @Order(2)
    public SecurityFilterChain partnerFilterChain(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder m = PathPatternRequestMatcher.withDefaults();

        http
            .securityMatcher(m.matcher("/api/v1/partner/**"))
            // No CORS: a partner is a server, not a browser, so there is no origin to
            // negotiate and nothing legitimate ever preflights this.
            .cors(AbstractHttpConfigurer::disable)
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .anonymous(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .exceptionHandling(e -> e
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

            .authorizeHttpRequests(auth -> auth
                .requestMatchers(m.matcher(HttpMethod.POST, "/api/v1/partner/fetch-details"))
                        .hasAnyAuthority(Role.VENDOR, Role.ADMIN)
                // Deny by default. A new partner endpoint here is unreachable until
                // somebody decides which role it needs, which is the correct way round.
                .anyRequest().denyAll())

            .addFilterBefore(partnerAuthFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain browserFilterChain(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder m = PathPatternRequestMatcher.withDefaults();

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))

            // Spring's CSRF token repository does not fit a cross-origin SPA. The
            // replacement is the __Host- cookie paired with the X-Session-Id header in
            // SessionContextFilter, plus SameSite=Strict and a CORS policy that will not
            // preflight an unknown origin.
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
                // ROLE_BORROWER, not ROLE_SESSION. A resolved session only proves the
                // browser completed the handshake; ROLE_BORROWER is set by
                // BorrowerAuthFilter after it verifies the JWT, so an endpoint that
                // forgets to look at the token is still gated here.
                //
                // The agreement half of the binding cannot be checked at this layer — it
                // is in the encrypted body — so FetchService calls requireAgreement.
                .requestMatchers(m.matcher(HttpMethod.POST, "/api/v1/payment/fetch-details"))
                        .hasAuthority(Role.BORROWER)
                .anyRequest().denyAll())

            // Order is the contract: replay check, then session resolution, then token
            // verification, which needs the session id the previous filter produced.
            .addFilterBefore(antiReplayFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(sessionContextFilter, AntiReplayFilter.class)
            .addFilterAfter(borrowerAuthFilter(), SessionContextFilter.class);

        return http.build();
    }
}
