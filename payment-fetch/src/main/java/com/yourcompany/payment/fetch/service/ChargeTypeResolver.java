package com.yourcompany.payment.fetch.service;

import com.yourcompany.payment.fetch.config.FetchProperties;
import com.yourcompany.payment.fetch.dto.FetchDtos;
import com.yourcompany.payment.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Validates the requested charge type and resolves what it means in money.
 *
 * SEAM, and the one place in this service where a guess would cost real money. The
 * mapping from a charge type to a figure was never supplied, so an unmapped charge type
 * yields a null payableAmount and the caller works from the components. Defaulting to
 * TOTAL_OUTSTANDING — the obvious-looking default — would quietly present a borrower
 * with a full closure amount when they asked to pay one instalment.
 *
 * Fill fetch.charge-type-amount when the business confirms it. Nothing else changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChargeTypeResolver {

    private final FetchProperties props;

    /** Rejects anything not in fetch.allowed-charge-types, and returns the canonical form. */
    public String validate(String chargeType) {
        String normalised = chargeType == null ? "" : chargeType.trim().toUpperCase();
        if (!props.getAllowedChargeTypes().contains(normalised)) {
            // The valid set is not echoed back: it is a business list, and enumerating it
            // for an unauthenticated guess is free reconnaissance.
            throw new ApiException("CHARGE_TYPE_INVALID",
                    "That payment type is not available.", HttpStatus.BAD_REQUEST);
        }
        return normalised;
    }

    /** Null when the charge type has no mapping configured yet. */
    public BigDecimal payableAmount(String chargeType, FetchDtos.Dues dues) {
        String field = props.getChargeTypeAmount().get(chargeType);
        if (field == null || field.isBlank()) {
            log.debug("No amount mapping for charge type {}; returning components only", chargeType);
            return null;
        }
        return switch (field.trim().toUpperCase()) {
            case "CURRENT_DUE" -> dues.getCurrentDue();
            case "PENAL_OVERDUE" -> dues.getPenalOverdue();
            case "OTHER_OVERDUE" -> dues.getOtherOverdue();
            case "TOTAL_OUTSTANDING" -> dues.getTotalOutstanding();
            case "ARREARS" -> dues.getArrears();
            default -> {
                // Configuration error, not a caller error. Loud, and no amount returned —
                // better than resolving a typo to the wrong figure.
                log.error("fetch.charge-type-amount maps {} to unknown field '{}'", chargeType, field);
                yield null;
            }
        };
    }
}
