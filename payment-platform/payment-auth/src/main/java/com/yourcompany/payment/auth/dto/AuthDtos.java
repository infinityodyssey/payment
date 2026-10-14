package com.yourcompany.payment.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request and response shapes for authentication.
 *
 * Every inbound field carries an explicit format constraint. Anything that reaches a
 * downstream system or a Redis key is constrained at the edge, not sanitised later.
 */
public final class AuthDtos {

    private AuthDtos() {}

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HandshakeRequest {
        /** Uncompressed ANSI X9.62 P-256 point: 0x04 || X || Y, hex encoded. */
        @NotBlank(message = "Client public key is required")
        @Pattern(regexp = "^04[0-9a-fA-F]{128}$", message = "Invalid client public key")
        private String clientPublicKeyHex;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HandshakeResponse {
        private String sessionId;
        private String serverPublicKeyHex;
        private long expiresInSeconds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyAgreementRequest {
        @NotBlank(message = "Agreement number is required")
        @Size(min = 6, max = 30)
        @Pattern(regexp = "^[A-Za-z0-9]{6,30}$", message = "Invalid agreement number")
        private String agreementNumber;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyAgreementResponse {
        private String maskedMobile;
        private long otpExpiresInSeconds;
        private long resendAvailableInSeconds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyOtpRequest {
        /** Five digits. Must stay in step with auth.otp.length. */
        @NotBlank(message = "Code is required")
        @Pattern(regexp = "^[0-9]{5}$", message = "Invalid code")
        private String otp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyOtpResponse {
        private String customerName;
        private String agreementNumber;
        private String paymentAuthToken;
        private String tokenType;
        private long expiresInSeconds;
    }
}
