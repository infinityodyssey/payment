package com.yourcompany.payment.shared.security;

import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.exception.SessionException;
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
 * Verifies the borrower JWT. The mirror of {@link PartnerAuthFilter} for the browser
 * journey.
 *
 * This exists so that no controller ever reads the Authorization header. Header parsing
 * in a controller means every new borrower endpoint has to remember to do it, the
 * forgetting is silent, and the filter chain cannot express "this path needs a verified
 * borrower token" — the chain could only check ROLE_SESSION, which proves the handshake
 * happened and nothing more.
 *
 * With this filter, ROLE_BORROWER becomes a matcher in SecurityConfig:
 *
 * <pre>
 *   .requestMatchers(...).hasAuthority(Role.BORROWER)
 * </pre>
 *
 * so an endpoint that forgets to look at the token is still gated by the chain.
 *
 * <h2>Why it checks only half the binding</h2>
 * It verifies the signature, the algorithm, the issuer, the audience, the expiry, and
 * that the token belongs to the session the request arrived on. It does NOT check the
 * agreement, because the agreement number is inside the AES-GCM encrypted body and a
 * servlet filter runs before the decryption advice. The service completes it with
 * {@link JwtService#requireAgreement} the moment the body exists.
 *
 * That split is a fact about servlet ordering, not a design preference, and it is the
 * reason the claims are published on the request: so the service can finish the job
 * without re-parsing anything.
 *
 * <h2>Mount it after SessionContextFilter</h2>
 * It needs the session id that filter resolves from the cookie/header pair. Mounted
 * before, there is no session to bind the token to and every request fails.
 *
 * Not a {@code @Component}: each service declares it as a bean in its own
 * SecurityConfig. An auto-registered filter runs on every request in every service that
 * has payment-shared on the classpath — including auth-service's handshake, where no
 * borrower token exists yet — and the only fix is remembering a FilterRegistrationBean
 * to switch it off again. Explicit beans make "this service needs this filter" a
 * statement rather than an omission.
 */
@Slf4j
@RequiredArgsConstructor
public class BorrowerAuthFilter extends OncePerRequestFilter {

    /** Request attribute holding the verified {@link TokenClaims}. */
    public static final String BORROWER_CLAIMS = "BORROWER_TOKEN_CLAIMS";

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final ApiErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            SessionContext session = (SessionContext) request.getAttribute(SessionContext.ATTRIBUTE);
            if (session == null) {
                // SessionContextFilter did not run, or ran after this one. A wiring
                // error; fail closed rather than verify a token against nothing.
                log.error("BorrowerAuthFilter ran without a session context — check filter order");
                throw SessionException.required();
            }

            String header = request.getHeader(SecurityHeaders.AUTHORIZATION);
            if (header == null || !header.startsWith(BEARER)) {
                throw new ApiException("TOKEN_MISSING",
                        "Your authorisation has expired. Please verify again.",
                        org.springframework.http.HttpStatus.UNAUTHORIZED);
            }

            TokenClaims claims = jwtService.verifyBorrowerToken(
                    header.substring(BEARER.length()), session.sessionId());

            if (!claims.hasRole(Role.BORROWER)) {
                throw new ApiException("TOKEN_INVALID",
                        "Your authorisation has expired. Please verify again.",
                        org.springframework.http.HttpStatus.UNAUTHORIZED);
            }

            request.setAttribute(BORROWER_CLAIMS, claims);

            // Replaces the ROLE_SESSION authentication SessionContextFilter set, so the
            // chain can require ROLE_BORROWER. The principal stays the session id.
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(session.sessionId(), null,
                            List.of(new SimpleGrantedAuthority(Role.BORROWER))));

            chain.doFilter(request, response);

        } catch (ApiException e) {
            SecurityContextHolder.clearContext();
            errorWriter.write(response, e);
        }
    }
}
