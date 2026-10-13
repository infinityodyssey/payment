package com.yourcompany.payment.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Append-only audit ledger.
 *
 * No setters and no update path: rows are inserted and never modified. Grant the
 * application's database user INSERT and SELECT only, so the service cannot rewrite
 * its own history even if compromised.
 *
 * The agreement number is stored in full because dispute handling needs it and the
 * database is an in-country, access-controlled store. It must still never appear in a
 * log line.
 */
@Entity
@Table(name = "PAYMENT_AUDIT_EVENTS")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "CORRELATION_ID", length = 36, nullable = false)
    private String correlationId;

    @Column(name = "EVENT_TYPE", length = 48, nullable = false)
    private String eventType;

    @Column(name = "OUTCOME", length = 16, nullable = false)
    private String outcome;

    @Column(name = "ERROR_CODE", length = 48)
    private String errorCode;

    /** Hash, not the id: the ledger should not become a second place session ids live. */
    @Column(name = "SESSION_REF", length = 32)
    private String sessionRef;

    @Column(name = "AGREEMENT_NUMBER", length = 50)
    private String agreementNumber;

    @Column(name = "CLIENT_IP_HASH", length = 32)
    private String clientIpHash;

    @Column(name = "USER_AGENT", length = 256)
    private String userAgent;

    @Column(name = "OCCURRED_AT", nullable = false)
    private Instant occurredAt;
}
