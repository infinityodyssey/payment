package com.yourcompany.payment.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The subset of the gold-loan response the platform models today.
 *
 * The full LMS payload is persisted to LMS_API_LOG for reconciliation. Dues
 * arithmetic, address handling, charge breakdowns and the loss-amount branch belong to
 * fetch-service and the payment phase; nothing is lost by not modelling them here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanDetails {
    private String agreementNumber;
    private String customerName;
    private String mobileNumber;
    private String maskedMobile;
    private String accountStatus;
    /** Entries in the LMS success array. See LmsApiClient.extract for why this matters. */
    private int agreementCount;
}
