package com.yourcompany.payment.auth.service;

import com.yourcompany.payment.auth.config.AuthProperties;
import com.yourcompany.payment.auth.controller.PartnerTokenController;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.security.PaymentTokenService;
import com.yourcompany.payment.shared.util.AuditEventType;
import com.yourcompany.payment.shared.web.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Issues partner bearer tokens from an API key and secret.
 *
 * Secrets are stored as bcrypt hashes in configuration, so a config dump or a secret read
 * does not yield a usable credential. Verification always runs the encoder, even for an
 * unknown partner code, so response timing does not reveal which codes exist.
 *
 * The token reuses the platform token format with the partner code in the session-id
 * position and no agreement bound, so posting-service verifies it with the same
 * PaymentTokenService the browser path uses. One token format, one verification path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerTokenService {

    /** Bcrypt hash of a value no partner has, for constant-work comparison on misses. */
    private static final String DUMMY_HASH =
            "$2a$12$usesomesillystringfore7hnbRJcxd0cQVGdfj.9jXq7L1XgkQ2i";

    private final AuthProperties props;
    private final PasswordEncoder passwordEncoder;
    private final PaymentTokenService paymentTokenService;
    private final AuditService auditService;

    public PartnerTokenController.PartnerTokenResponse issue(String partnerCode, String apiSecret,
                                                             HttpServletRequest http) {
        String storedHash = props.getPartner().getCredentials().get(partnerCode);
        boolean matches = passwordEncoder.matches(apiSecret, storedHash == null ? DUMMY_HASH : storedHash);

        if (storedHash == null || !matches) {
            auditService.record(AuditEventType.PARTNER_TOKEN_ISSUED, AuditEventType.FAILURE,
                    "PARTNER_AUTH_FAILED", null, null, http);
            log.warn("Partner authentication failed for code {}", partnerCode);
            // Same message whether the code is unknown or the secret is wrong.
            throw new ApiException("PARTNER_AUTH_FAILED",
                    "Authentication failed.", HttpStatus.UNAUTHORIZED);
        }

        var issued = paymentTokenService.issuePartnerToken(partnerCode);

        auditService.record(AuditEventType.PARTNER_TOKEN_ISSUED, AuditEventType.SUCCESS, null,
                null, null, http);

        return PartnerTokenController.PartnerTokenResponse.builder()
                .accessToken(issued.token())
                .tokenType("Bearer")
                .expiresInSeconds(props.getPartner().getTokenTtlSeconds())
                .build();
    }
}
