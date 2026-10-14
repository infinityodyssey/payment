package com.yourcompany.payment.shared.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Configuration every service needs. Service-specific settings live in that service's
 * own properties class.
 *
 * The typed replacement for the legacy globalConfiguration bean: no magic strings
 * anywhere else in the codebase.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app")
public class SharedProperties {

    private Security security = new Security();
    private Global global = new Global();

    @Data
    public static class Security {
        /** Signs and verifies payment tokens. Identical across every service. */
        @NotBlank
        private String tokenHmacSecret;

        @Min(60) private long sessionTtlSeconds = 300;      // 5 minutes, sliding
        @Min(60) private long tokenTtlSeconds = 300;        // dies with the session
        @Min(10) private long nonceWindowSeconds = 60;
        @Min(0)  private long clockSkewToleranceSeconds = 5;

        private boolean requireEncryptedPayloads = true;

        /** Proxies in front of this service that append to X-Forwarded-For. */
        @Min(0) private int trustedProxyCount = 2;

        private String actuatorUsername = "prometheus";
        private String actuatorPassword;

        private Cors cors = new Cors();

        @Data
        public static class Cors {
            @NotEmpty private List<String> allowedOrigins;
        }
    }

    /** Upstream endpoints and constants. */
    @Data
    public static class Global {
        @NotBlank private String fiid = "1";

        @NotBlank private String lmsTokenUrl;
        @NotBlank private String lmsFetchUrl;

        /**
         * Posting-phase endpoints. Not @NotBlank on purpose: auth-service never calls
         * them and must not fail to start because a posting URL is unset. LmsApiClient
         * refuses the call with a clear message if one is missing where it is needed.
         */
        private String lmsOtsUrl;
        private String lmsStatusUrl;
        private String lmsPostUrl;

        private String lmsTokenJsonField = "token";
        @Min(60) private long lmsTokenTtlSeconds = 1500;
        @Min(5)  private int lmsTimeoutSeconds = 30;
        @Min(1)  private int lmsMaxConcurrent = 100;
        private String lmsTopic = "payment.lms.interaction.v1";
    }
}
