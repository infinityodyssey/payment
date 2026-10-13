package com.yourcompany.payment.auth.controller;

import com.yourcompany.payment.auth.service.PartnerTokenService;
import com.yourcompany.payment.shared.crypto.SkipEncryption;
import com.yourcompany.payment.shared.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * Partner credential exchange.
 *
 * Partners are machines over mTLS, so there is no ECDH handshake and no encrypted
 * envelope — hence @SkipEncryption on the class. Transport security is the TLS layer's
 * job here, which is a genuinely different model from the browser path.
 */
@SkipEncryption
@RestController
@RequestMapping("/api/v1/partner")
@RequiredArgsConstructor
public class PartnerTokenController {

    private final PartnerTokenService partnerTokenService;

    @PostMapping("/token")
    public ResponseEntity<ApiResponse<PartnerTokenResponse>> token(
            @Valid @RequestBody PartnerTokenRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(
                partnerTokenService.issue(request.getPartnerCode(), request.getApiSecret(), http)));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartnerTokenRequest {
        @NotBlank private String partnerCode;
        @NotBlank private String apiSecret;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartnerTokenResponse {
        private String accessToken;
        private String tokenType;
        private long expiresInSeconds;
    }
}
