package com.yourcompany.payment.worker;

import com.yourcompany.payment.shared.config.SharedConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * payment-worker: posting queue to LMS, sweeper, reconciliation. No ingress.
 *
 * SKELETON. See README.md in this module for what belongs here and what must not.
 */
@SpringBootApplication
@Import(SharedConfig.class)
public class WorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
