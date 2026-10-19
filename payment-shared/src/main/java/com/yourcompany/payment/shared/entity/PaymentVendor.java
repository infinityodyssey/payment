package com.yourcompany.payment.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * An approved payment vendor. Read-only — enabling or disabling one is a DBA action.
 *
 * In payment-shared rather than in fetch-service because payment-service needs the same
 * rows: fetch validates the vendor a caller asked for, payment-service generates the
 * tvstxnid from TVS_PREFIX, and reconciliation parses that prefix back.
 */
@Entity
@Table(name = "TVSCS_PAYMENT_VENDOR_PG")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentVendor {

    @Id
    @Column(name = "VENDOR_CODE", length = 32, nullable = false)
    private String vendorCode;

    @Column(name = "VENDOR_NAME", length = 64, nullable = false)
    private String vendorName;

    /** First two characters of every tvstxnid for this vendor. Unique across the table. */
    @Column(name = "TVS_PREFIX", length = 2, nullable = false)
    private String tvsPrefix;

    @Column(name = "ACTIVE", length = 1, nullable = false)
    private String active;

    @Column(name = "MIN_AMOUNT", nullable = false)
    private BigDecimal minAmount;

    /** Null means no upper limit. */
    @Column(name = "MAX_AMOUNT")
    private BigDecimal maxAmount;

    @Column(name = "PRIORITY", nullable = false)
    private Integer priority;

    public boolean isActive() {
        return "Y".equalsIgnoreCase(active);
    }

    public boolean accepts(BigDecimal amount) {
        if (amount == null) {
            return true;
        }
        if (minAmount != null && amount.compareTo(minAmount) < 0) {
            return false;
        }
        return maxAmount == null || amount.compareTo(maxAmount) <= 0;
    }
}
