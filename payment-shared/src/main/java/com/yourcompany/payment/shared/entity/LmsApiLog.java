package com.yourcompany.payment.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Every LMS or SMS call, insert only. Replaces the legacy goldLogDetails table.
 *
 * Request and response bodies are stored in full because reconciliation and dispute
 * handling need the exact payload. This is the right home for them: the grant and the
 * retention here are yours to set, unlike a log aggregator's.
 *
 * SMS rows never contain the OTP — the endpoint and response have the code and mobile
 * number redacted before the row is written.
 */
@Entity
@Table(name = "LMS_API_LOG")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LmsApiLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "CORRELATION_ID", length = 36, nullable = false)
    private String correlationId;

    /** Present for posting-related calls; null for authentication lookups. */
    @Column(name = "TXN_REFERENCE", length = 40)
    private String txnReference;

    /** Which posting attempt this call belonged to. Null outside the posting flow. */
    @Column(name = "ATTEMPT_NO")
    private Integer attemptNo;

    /** STATUS_CHECK | AGREEMENT_FETCH | OTS_AMOUNT | POST_TXN | LMS_TOKEN | SMS_TOKEN | SMS_SEND */
    @Column(name = "API_NAME", length = 32, nullable = false)
    private String apiName;

    @Column(name = "ENDPOINT", length = 512, nullable = false)
    private String endpoint;

    @Column(name = "HTTP_METHOD", length = 8, nullable = false)
    private String httpMethod;

    @Column(name = "AGREEMENT_NUMBER", length = 50)
    private String agreementNumber;

    @Lob
    @Column(name = "REQUEST_BODY")
    private String requestBody;

    @Lob
    @Column(name = "RESPONSE_BODY")
    private String responseBody;

    @Column(name = "HTTP_STATUS")
    private Integer httpStatus;

    /** The upstream's own status field, e.g. "Success". */
    @Column(name = "UPSTREAM_STATUS", length = 64)
    private String upstreamStatus;

    @Column(name = "UPSTREAM_CODE", length = 32)
    private String upstreamCode;

    @Column(name = "OUTCOME", length = 16, nullable = false)
    private String outcome;

    @Column(name = "ERROR_DETAIL", length = 512)
    private String errorDetail;

    @Column(name = "DURATION_MS")
    private Long durationMs;

    @Column(name = "REQUESTED_AT", nullable = false)
    private Instant requestedAt;

    @Column(name = "RESPONDED_AT")
    private Instant respondedAt;
}
