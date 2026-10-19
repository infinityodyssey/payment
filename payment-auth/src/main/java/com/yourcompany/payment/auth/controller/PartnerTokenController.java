package com.yourcompany.payment.auth.controller;

import com.yourcompany.payment.auth.service.PartnerTokenService;
import com.yourcompany.payment.shared.crypto.SkipEncryption;
import com.yourcompany.payment.shared.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Credential exchange for machine accounts — partners and internal tools.
 *
 * Two forms, same result:
 *
 *   { "username": "acme_vendor", "password": "..." }
 *   { "apiKey": "<one encrypted string holding both>" }
 *
 * The response is a JWT valid for 15 minutes, carrying the role from
 * TVSCS_ONLINE_USER_PG.
 *
 * A `role` field is accepted because existing clients send one. It is compared against
 * the database and then discarded. If the role came from the request, a vendor who
 * knows their own password could ask for ADMIN and get it.
 *
 * No ECDH and no encrypted envelope — these are machines over TLS, hence
 * {@code @SkipEncryption}. A genuinely different model from the browser path.
 */
@SkipEncryption
@RestController
@RequestMapping("/api/v1/partner")
@RequiredArgsConstructor
public class PartnerTokenController {

    private final PartnerTokenService partnerTokenService;

    @PostMapping("/token")
    public ResponseEntity<ApiResponse<PartnerTokenResponse>> token(
            @RequestBody PartnerTokenRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(partnerTokenService.issue(request, http)));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartnerTokenRequest {
        /** With password. Omit if using apiKey. */
        private String username;
        private String password;

        /** The single encrypted string holding username and password. */
        private String apiKey;

        /**
         * Accepted and ignored. The role in the issued token always comes from
         * TVSCS_ONLINE_USER_PG; a mismatch with this value is logged.
         *
         * No bean validation on these fields on purpose: a 400 listing which field was
         * missing tells an attacker which form of credential the endpoint expects. Every
         * bad request gets the same 401 from the service.
         */
        private String role;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartnerTokenResponse {
        private String accessToken;
        private String tokenType;
        private long expiresInSeconds;
        /** ADMIN or VENDOR, as stored. So the caller can see what they actually got. */
        private String role;
    }
}
