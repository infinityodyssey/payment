package com.yourcompany.payment.fetch.service;

import com.yourcompany.payment.fetch.dto.FetchDtos;
import com.yourcompany.payment.shared.entity.PaymentVendor;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.repo.PaymentVendorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Checks the vendor a caller asked for against TVSCS_PAYMENT_VENDOR_PG.
 *
 * Three distinct outcomes, and they are distinct on purpose:
 *
 *   unknown code    -> 400. A client bug or someone probing. Nothing the borrower can do.
 *   inactive        -> 503 VENDOR_UNAVAILABLE. Temporary and actionable: pick another.
 *   amount refused  -> 400 with the limit. The borrower can change the amount.
 *
 * Collapsing these into one message is what produces the support ticket that says "it
 * just says error".
 *
 * QUERIED EVERY REQUEST, NOT CACHED. A primary-key read on a ten-row table costs
 * microseconds from Oracle's buffer cache, so a cache would save nothing measurable and
 * would cost the thing that matters: when operations set ACTIVE='N' because a vendor is
 * down, it has to take effect on the next request, not after a TTL. This is the opposite
 * call from the token endpoint, where the work was 250ms of bcrypt rather than a tiny
 * indexed read.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendorValidator {

    private final PaymentVendorRepository vendors;

    public FetchDtos.Vendor validate(String vendorCode, BigDecimal payableAmount) {
        String code = vendorCode == null ? "" : vendorCode.trim().toUpperCase();

        PaymentVendor vendor = vendors.findByVendorCode(code)
                .orElseThrow(() -> {
                    log.warn("Unknown payment vendor requested: {}", code);
                    return new ApiException("VENDOR_INVALID",
                            "That payment option is not recognised.", HttpStatus.BAD_REQUEST);
                });

        if (!vendor.isActive()) {
            log.info("Vendor {} requested while inactive", code);
            throw new ApiException("VENDOR_UNAVAILABLE",
                    "That payment option is temporarily unavailable. Please choose another.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        // Only when the amount is known. An unmapped charge type leaves it null, and a
        // limit check against null would either pass everything or fail everything —
        // both worse than not checking.
        if (payableAmount != null && !vendor.accepts(payableAmount)) {
            log.info("Vendor {} rejected amount {} (min={}, max={})",
                    code, payableAmount, vendor.getMinAmount(), vendor.getMaxAmount());
            throw new ApiException("VENDOR_AMOUNT_NOT_SUPPORTED",
                    "This amount cannot be paid through that option. Please choose another.",
                    HttpStatus.BAD_REQUEST);
        }

        return FetchDtos.Vendor.builder()
                .vendorCode(vendor.getVendorCode())
                .vendorName(vendor.getVendorName())
                .tvsPrefix(vendor.getTvsPrefix())
                .build();
    }
}
