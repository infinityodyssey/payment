package com.yourcompany.payment.shared.web;

import com.yourcompany.payment.shared.util.Hashing;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Client IP and a coarse fingerprint.
 *
 * The IP is used for the audit trail only. Per-IP rate limiting belongs to the API
 * gateway — note that the gateway cannot enforce the per-agreement OTP send budget or the
 * attempt cap, because those key on a value inside an encrypted body. Those live in
 * OtpService and must stay there.
 */
@Component
public class ClientContext {

    /**
     * How many proxies sit in front of this service.
     *
     * X-Forwarded-For is appended to by each hop, so the client's real address is
     * trusted-proxy-count entries from the right, not the leftmost entry. Taking the
     * leftmost value trusts whatever the caller put there: an attacker sets
     * "X-Forwarded-For: 1.2.3.4" and every audit row records a fabricated address.
     *
     * Set this to the number of proxies that actually append a value — typically the API
     * gateway plus the load balancer. If it is ever wrong, the audit trail records the
     * wrong address, which is worse than recording none.
     */
    @Value("${app.security.trusted-proxy-count:2}")
    private int trustedProxyCount;

    public String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            int index = hops.length - trustedProxyCount;
            if (index >= 0 && index < hops.length) {
                String candidate = hops[index].trim();
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
            // Fewer hops than configured means the request did not traverse the expected
            // path. Fall back to the socket address rather than trusting a header the
            // caller may have written.
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    public String hashIp(HttpServletRequest request) {
        return Hashing.keyHash(clientIp(request));
    }

    /**
     * Deliberately weak: User-Agent and Accept-Language, no IP, because mobile users
     * change IP mid-journey. It breaks trivial cookie replay from another tool; it will
     * not stop an attacker who already holds the cookie.
     */
    public String fingerprint(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        String lang = request.getHeader("Accept-Language");
        return Hashing.shortHash((ua == null ? "" : ua) + "|" + (lang == null ? "" : lang));
    }
}
