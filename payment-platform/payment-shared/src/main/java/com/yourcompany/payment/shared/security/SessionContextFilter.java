package com.yourcompany.payment.shared.security;

import com.yourcompany.payment.shared.config.SharedProperties;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.exception.SessionException;
import com.yourcompany.payment.shared.session.SessionKeyReader;
import com.yourcompany.payment.shared.session.SessionKeys;
import com.yourcompany.payment.shared.web.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;

/**
 * Resolves the session for browser requests, in every service.
 *
 * The __Host- prefixed, HttpOnly, Secure, SameSite=Strict cookie is authoritative and the
 * X-Session-Id header must be present and identical.
 *
 * That pairing is the CSRF control. Spring's CSRF token repository does not fit a
 * cross-origin SPA, so this is a double-submit: a cross-site attacker can make the
 * browser attach the cookie but cannot read it to populate the header, and cannot set a
 * custom header on a simple request without a preflight CORS will refuse.
 *
 * This filter reads the session key and nothing else. It does not load session state,
 * which is why fetch-service and payment-service can run without any ability to see or
 * modify the state machine.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionContextFilter extends OncePerRequestFilter {

    private final SessionKeyReader sessionKeyReader;
    private final SharedProperties props;
    private final ApiErrorWriter errorWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || "OPTIONS".equals(request.getMethod())
                || path.equals("/api/v1/auth/handshake")
                || path.startsWith("/api/v1/partner/")
                || path.startsWith("/api/v1/payment/webhook/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String cookieValue = readCookie(request);
            String headerValue = request.getHeader(SecurityHeaders.SESSION_ID);

            if (cookieValue == null || headerValue == null) {
                throw SessionException.required();
            }
            if (!MessageDigest.isEqual(cookieValue.getBytes(StandardCharsets.UTF_8),
                    headerValue.getBytes(StandardCharsets.UTF_8))) {
                log.warn("Session cookie and header mismatch; possible cross-site request");
                throw SessionException.required();
            }
            if (!SessionKeys.isWellFormedId(cookieValue)) {
                throw SessionException.required();
            }

            byte[] key = sessionKeyReader.sessionKey(cookieValue);

            // Sliding window: the TTL measures idle time, not total time. Extending it
            // here is a TTL touch, not a state write, so it does not break the rule that
            // only auth-service writes session state.
            sessionKeyReader.touch(cookieValue,
                    Duration.ofSeconds(props.getSecurity().getSessionTtlSeconds()));

            request.setAttribute(SessionContext.ATTRIBUTE, new SessionContext(cookieValue, key));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(cookieValue, null,
                            List.of(new SimpleGrantedAuthority("ROLE_SESSION"))));

            chain.doFilter(request, response);

        } catch (ApiException e) {
            SecurityContextHolder.clearContext();
            errorWriter.write(response, e);
        }
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (SecurityHeaders.SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
