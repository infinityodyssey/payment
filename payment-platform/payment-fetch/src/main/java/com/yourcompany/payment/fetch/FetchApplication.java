package com.yourcompany.payment.fetch;

import com.yourcompany.payment.shared.config.SharedConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * fetch-service: agreement and customer details from the LMS. Read-only.
 *
 * SKELETON. See README.md in this module for what belongs here and what must not.
 */
@SpringBootApplication
@Import(SharedConfig.class)
public class FetchApplication {
    public static void main(String[] args) {
        SpringApplication.run(FetchApplication.class, args);
    }
}
