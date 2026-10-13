package com.yourcompany.payment.auth.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.Map;

/** Settings only auth-service needs. Everything shared lives in SharedProperties. */
@Data
@Validated
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private Otp otp = new Otp();
    private Sms sms = new Sms();
    private Partner partner = new Partner();

    /** Mixed into OTP hashes so a Redis dump alone cannot brute-force them. */
    @NotBlank
    private String otpPepper;

    @Data
    public static class Otp {
        /** Five digits. The DTO pattern must stay in step with this value. */
        @Min(4) private int length = 5;
        @Min(30) private long ttlSeconds = 180;
        @Min(1) private int maxAttempts = 3;
        @Min(15) private long resendCooldownSeconds = 60;
        @Min(1) private int maxSendsPerAgreementPerHour = 5;
        @Min(60) private long lockoutSeconds = 900;
    }

    @Data
    public static class Sms {
        @NotBlank private String tokenUrl;
        @NotBlank private String sendUrl;
        private String tokenJsonField = "access_token";
        @Min(60) private long tokenTtlSeconds = 1500;
        @Min(5) private int timeoutSeconds = 15;
        @Min(1) private int maxConcurrent = 30;

        private String lang = "EN";
        private String purpose = "1001";
        private String template;
        private String empId;
        /** DLT-registered wording. Only {otp} is substituted. */
        private String messageTemplate;
    }

    @Data
    public static class Partner {
        @Min(60) private long tokenTtlSeconds = 900;
        /**
         * partnerCode -> bcrypt hash of the partner's API secret.
         *
         * Hashes, not secrets: a config dump or a Kubernetes secret read must not yield
         * a usable credential. Generate with any bcrypt tool at cost 12 or higher.
         */
        private Map<String, String> credentials = new HashMap<>();
    }
}
