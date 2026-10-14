package com.yourcompany.payment.posting;

import com.yourcompany.payment.shared.config.SharedConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * posting-service: partner direct posting intake.
 *
 * SKELETON. See README.md in this module for what belongs here and what must not.
 */
@SpringBootApplication
@Import(SharedConfig.class)
public class PostingApplication {
    public static void main(String[] args) {
        SpringApplication.run(PostingApplication.class, args);
    }
}
