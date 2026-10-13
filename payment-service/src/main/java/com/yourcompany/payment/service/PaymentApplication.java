package com.yourcompany.payment.service;

import com.yourcompany.payment.shared.config.SharedConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * payment-service: browser payment lifecycle, vendor adapters, gateway callbacks.
 *
 * SKELETON. See README.md in this module for what belongs here and what must not.
 */
@SpringBootApplication
@Import(SharedConfig.class)
public class PaymentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
