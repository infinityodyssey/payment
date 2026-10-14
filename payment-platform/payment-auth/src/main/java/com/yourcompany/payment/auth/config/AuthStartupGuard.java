package com.yourcompany.payment.auth.config;

import com.yourcompany.payment.shared.config.SecretsValidator;
import com.yourcompany.payment.shared.config.SharedProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Startup checks specific to auth-service.
 *
 * The plaintext-payload flag exists for developer convenience and is exactly how an
 * encryption bypass reaches production: someone flips it to debug an integration, the
 * change survives a merge, and nothing visibly breaks. Binding it to the profile makes
 * disabling encryption in a deployed environment a reviewable act rather than a silent
 * one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthStartupGuard {

    private static final List<String> PERMITTED = List.of("dev", "test");

    private final AuthProperties authProps;
    private final SharedProperties sharedProps;
    private final Environment environment;

    @PostConstruct
    void validate() {
        SecretsValidator.check("auth.otp-pepper", authProps.getOtpPepper());

        if (authProps.getSms().getMessageTemplate() == null
                || !authProps.getSms().getMessageTemplate().contains("{otp}")) {
            throw new IllegalStateException(
                    "auth.sms.message-template must contain {otp}. Refusing to start.");
        }

        List<String> active = Arrays.asList(environment.getActiveProfiles());
        boolean permitted = !active.isEmpty() && PERMITTED.containsAll(active);

        if (!sharedProps.getSecurity().isRequireEncryptedPayloads()) {
            if (!permitted) {
                throw new IllegalStateException(
                        "app.security.require-encrypted-payloads is false under profiles " + active
                                + ". Permitted only under " + PERMITTED + ". Refusing to start.");
            }
            log.warn("PAYLOAD ENCRYPTION DISABLED under profiles {}. Bodies are plaintext.", active);
        }
    }
}
