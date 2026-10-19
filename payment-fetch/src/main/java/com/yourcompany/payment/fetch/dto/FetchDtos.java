package com.yourcompany.payment.fetch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** Request and response for the one endpoint this service serves. */
public final class FetchDtos {

    private FetchDtos() {}

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FetchRequest {

        /**
         * For a PARTNER caller this selects the agreement, because a partner token is
         * bound to none.
         *
         * For a BORROWER caller it selects NOTHING. The agreement comes from the session —
         * auth-service stored it when it looked the borrower up to send the OTP — and a
         * value here that disagrees is logged and ignored. It is read only as a fallback
         * when the session snapshot has expired, and even then the token's agreement hash
         * has to match it, so it cannot be used to reach another loan.
         */
        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "[A-Za-z0-9/-]{6,50}", message = "invalid agreement number")
        private String agreementNumber;

        /**
         * Which amount the caller intends to pay. Validated against
         * fetch.allowed-charge-types — an unrecognised value is rejected rather than
         * silently defaulting to the full outstanding, which would be the worst possible
         * default.
         */
        @NotBlank
        @Size(max = 32)
        private String chargeType;

        /**
         * The vendor the caller wants to pay through. Checked against
         * TVSCS_PAYMENT_VENDOR_PG on every request: exists, active, and accepts the
         * payable amount.
         */
        @NotBlank
        @Size(max = 32)
        private String paymentVendor;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FetchResponse {

        private Customer customer;
        private Dues dues;
        private Vendor vendor;
        private String chargeType;

        /**
         * True when the figures came from the session snapshot taken at OTP dispatch
         * rather than a fresh LMS call.
         *
         * It is here because it changes what the number means. A snapshot is minutes old
         * and the borrower may have paid elsewhere since, so payment initiation must
         * re-check the amount against the LMS regardless of what this says. Display is
         * safe; charging is not.
         */
        private boolean fromSessionSnapshot;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Customer {
        private String agreementNumber;
        private String customerName;
        /** Masked. The full number is never returned to a caller. */
        private String maskedMobile;
        private String accountStatus;
        private String openingDate;
        private String expiryDate;
        private String nextDueDate;
        private String lastPaymentDate;
        private Address address;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Address {
        private String addressType;
        private String line1;
        private String line2;
        private String landmark;
        private String city;
        private String taluk;
        private String town;
        private String state;
        private String pincode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Dues {
        private BigDecimal currentDue;
        private BigDecimal penalOverdue;
        private BigDecimal otherOverdue;
        private BigDecimal principalOverdue;
        private BigDecimal interestOverdue;
        private BigDecimal bounceChargesOverdue;
        private BigDecimal totalOutstanding;
        private BigDecimal totalPrincipalOutstanding;
        private BigDecimal arrears;
        private BigDecimal excess;
        private BigDecimal perDayInterest;
        private BigDecimal loanAmount;
        private BigDecimal sanctionAmount;
        private Integer dpdDays;

        /**
         * What the caller should actually be charged for the requested chargeType.
         *
         * SEAM. Which component a charge type maps to is a business rule that was never
         * supplied — see ChargeTypeResolver. Until it is confirmed, this is null for any
         * charge type without an explicit mapping, and the caller gets the components
         * above to work from. Guessing here would be guessing how much to take from a
         * borrower.
         */
        private BigDecimal payableAmount;

        /** Charge-wise breakdown as the LMS returned it, unaggregated. */
        private List<ChargeDue> charges;

        /** Number of entries in the LMS success array, i.e. accounts under this customer. */
        private int agreementCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChargeDue {
        private String chargeCode;
        private String chargeName;
        private BigDecimal chargeDue;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Vendor {
        private String vendorCode;
        private String vendorName;
        private String tvsPrefix;
    }
}
