package com.yourcompany.payment.shared.security;

import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.web.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates a partner or admin request from the bearer JWT. One HMAC, no database,
 * no Redis.
 *
 * This is what "the role is in the token" buys: the role becomes a Spring Security
 * authority, so authorisation is a matcher in SecurityConfig —
 * {@code hasAnyAuthority(Role.VENDOR, Role.ADMIN)} — rather than a check each controller
 * has to remember. A caller whose token says VENDOR is refused at the chain, before a
 * handler runs.
 *
 * The principal is the username from inside the signature, never a header the caller
 * sets.
 *
 * Mount it only on the paths that need it: it rejects a request with no Authorization
 * header, which is correct for a partner endpoint and wrong for the token endpoint
 * itself.
 *
 * Not a {@code @Component}: each service declares it as a bean in its own SecurityConfig.
 * An auto-registered filter runs on every request in every service with payment-shared on
 * the classpath — including /api/v1/partner/token, which has no bearer token yet, so no
 * partner could ever obtain one — and the only fix is remembering a
 * FilterRegistrationBean to switch it off again. An explicit bean makes "this service
 * needs this filter" a statement rather than an omission.
 */
@Slf4j
@RequiredArgsConstructor
public class PartnerAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final ApiErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            errorWriter.write(response, new ApiException("PARTNER_TOKEN_MISSING",
                    "Authentication required.", org.springframework.http.HttpStatus.UNAUTHORIZED));
            return;
        }

        try {
            TokenClaims claims = jwtService.verifyUserToken(header.substring(BEARER.length()));

            List<SimpleGrantedAuthority> authorities = claims.roles().stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();

            // The principal is the partner code taken from the verified token, not from
            // a header the caller sets. That distinction is the whole reason the subject
            // is inside the signature.
            var authentication = new UsernamePasswordAuthenticationToken(
                    claims.subject(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            request.setAttribute(PARTNER_CLAIMS, claims);

            chain.doFilter(request, response);

        } catch (ApiException e) {
            log.warn("Partner request rejected: {}", e.getErrorCode());
            errorWriter.write(response, e);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Request attribute holding the verified {@link TokenClaims}. */
    public static final String PARTNER_CLAIMS = "PARTNER_TOKEN_CLAIMS";
}
