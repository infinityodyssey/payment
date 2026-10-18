package com.yourcompany.payment.shared.security;

import com.yourcompany.payment.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Set;

/**
 * What a verified JWT says. Only ever produced by {@link JwtService} after the signature
 * has been checked — there is no other way to construct one from a request, which is the
 * point: a controller cannot accidentally read claims out of an unverified token.
 *
 * @param subject sessionId for a borrower token, partner code for a partner token
 * @param tokenId the jti, for correlating the audit trail
 * @param roles   what the holder may do. Authorisation reads this and nothing else.
 */
public record TokenClaims(String subject, String tokenId, Set<String> roles, Instant expiresAt) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /**
     * 403, not 401. The token is valid and the caller is who they say they are; they are
     * simply not allowed to do this. Returning 401 would tell a partner to go and fetch a
     * new token, which will have exactly the same roles.
     */
    public void requireRole(String role) {
        if (!hasRole(role)) {
            throw new ApiException("FORBIDDEN",
                    "This operation is not permitted for your credentials.", HttpStatus.FORBIDDEN);
        }
    }

    public long secondsRemaining() {
        return Math.max(0, expiresAt.getEpochSecond() - Instant.now().getEpochSecond());
    }
}
