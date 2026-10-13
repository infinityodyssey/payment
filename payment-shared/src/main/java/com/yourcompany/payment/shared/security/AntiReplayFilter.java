package com.yourcompany.payment.shared.security;

import com.yourcompany.payment.shared.config.SharedProperties;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.exception.ReplayException;
import com.yourcompany.payment.shared.exception.StateStoreUnavailableException;
import com.yourcompany.payment.shared.session.SessionKeys;
import com.yourcompany.payment.shared.web.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Mandatory anti-replay on every state-changing browser request.
 *
 * The headers are REQUIRED. The legacy filter validated them only when the client chose
 * to send them, so a replayer simply omitted the headers and the check never ran.
 *
 * There is no in-memory fallback: a per-node nonce set does not de-duplicate across a
 * load-balanced cluster, so it does not prevent replay at all.
 *
 * Nonces are shared across services because they share one Redis. A nonce spent on
 * auth-service cannot be reused against payment-service, which is the intent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AntiReplayFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;
    private final SharedProperties props;
    private final ApiErrorWriter errorWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/")
                || "GET".equals(request.getMethod())
                || "OPTIONS".equals(request.getMethod())
                // Partner and gateway traffic authenticates differently and does not
                // carry browser nonces; those paths have their own replay controls.
                || request.getRequestURI().startsWith("/api/v1/partner/")
                || request.getRequestURI().startsWith("/api/v1/payment/webhook/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String nonce = request.getHeader(SecurityHeaders.NONCE);
            requireFreshness(nonce, request.getHeader(SecurityHeaders.TIMESTAMP));
            claimNonce(nonce);
        } catch (ApiException e) {
            errorWriter.write(response, e);
            return;
        }
        chain.doFilter(request, response);
    }

    private void requireFreshness(String nonce, String timestamp) {
        if (nonce == null || nonce.isBlank() || timestamp == null || timestamp.isBlank()) {
            throw new ReplayException("REQUEST_HEADERS_MISSING",
                    "Request rejected. Please reload the page and try again.");
        }
        if (nonce.length() < 32 || nonce.length() > 64 || !nonce.matches("[0-9a-fA-F-]+")) {
            throw new ReplayException("REQUEST_NONCE_INVALID",
                    "Request rejected. Please reload the page and try again.");
        }
        long sent;
        try {
            sent = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new ReplayException("REQUEST_TIMESTAMP_INVALID",
                    "Request rejected. Please reload the page and try again.");
        }
        if (Math.abs(System.currentTimeMillis() - sent) > props.getSecurity().getNonceWindowSeconds() * 1000L) {
            throw new ReplayException("REQUEST_EXPIRED", "Your request took too long. Please try again.");
        }
    }

    private void claimNonce(String nonce) {
        // TTL matches the freshness window: outside it the timestamp check already
        // rejects, so retaining the nonce longer costs memory and buys nothing.
        Duration ttl = Duration.ofSeconds(props.getSecurity().getNonceWindowSeconds() + 5);
        Boolean claimed;
        try {
            claimed = redis.opsForValue().setIfAbsent(SessionKeys.nonce(nonce.toLowerCase()), "1", ttl);
        } catch (RuntimeException e) {
            log.error("Nonce store unavailable: {}", e.getClass().getSimpleName());
            throw new StateStoreUnavailableException();
        }
        if (claimed == null) {
            throw new StateStoreUnavailableException();
        }
        if (!claimed) {
            log.warn("Duplicate nonce rejected");
            throw new ReplayException("REQUEST_REPLAYED",
                    "This request was already processed. Please reload the page.");
        }
    }
}
