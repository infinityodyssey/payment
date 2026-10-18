package com.yourcompany.payment.auth;

import com.yourcompany.payment.auth.config.AuthProperties;
import com.yourcompany.payment.shared.config.SharedConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * auth-service: handshake, ECDH, OTP, browser and partner tokens.
 *
 * The only service that writes session state. Everything else reads a token signature
 * and the session key.
 */
@SpringBootApplication
@Import(SharedConfig.class)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
