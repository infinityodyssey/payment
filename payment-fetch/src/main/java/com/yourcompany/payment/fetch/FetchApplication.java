package com.yourcompany.payment.fetch;

import com.yourcompany.payment.fetch.config.FetchProperties;
import com.yourcompany.payment.shared.config.SharedConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * fetch-service: agreement and customer details. Read-only — it writes nothing anywhere.
 *
 * One capability, two front doors:
 *   POST /api/v1/payment/fetch-details   borrower, session + borrower JWT, encrypted body
 *   POST /api/v1/partner/fetch-details   partner, bearer JWT with role VENDOR or ADMIN
 *
 * The entity and repository scans are explicit because the JPA entities live in
 * payment-shared, outside this application's own package. Without them the vendor table
 * is invisible and the context fails to start with an unhelpful message about a missing
 * repository bean.
 */
@SpringBootApplication
@Import(SharedConfig.class)
@EntityScan(basePackages = "com.yourcompany.payment.shared.entity")
@EnableJpaRepositories(basePackages = "com.yourcompany.payment.shared.repo")
@EnableConfigurationProperties(FetchProperties.class)
public class FetchApplication {
    public static void main(String[] args) {
        SpringApplication.run(FetchApplication.class, args);
    }
}
