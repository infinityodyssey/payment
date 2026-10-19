package com.yourcompany.payment.fetch.service;

import com.yourcompany.payment.fetch.config.FetchProperties;
import com.yourcompany.payment.fetch.dto.FetchDtos;
import com.yourcompany.payment.shared.util.Mask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns the LMS gold-loan payload into the dues and customer response.
 *
 * This is the arithmetic from the legacy getGoldDueDetails, ported rather than
 * reinvented, because it encodes rules nobody wrote down anywhere else:
 *
 *   - Figures are SUMMED ACROSS success[], not taken from success[0]. One customer can
 *     hold several gold-loan accounts and the borrower pays one total.
 *
 *   - Visit charges (CCVCHGCD, CCVCHG) are SUBTRACTED from both currentdue and
 *     otherOverdue. The LMS includes them in those totals; this platform does not
 *     collect them, so leaving them in would overcharge every borrower who has one.
 *
 *   - The loss-amount branch. If any account is status 'L' the customer status is 'L'.
 *     Otherwise, if a loss amount exists on a non-'L' account, the status becomes
 *     'LOSSAMT' and the loss amount REPLACES the current due rather than adding to it.
 *
 *   - Seized and repossessed sub-statuses override the status with 'SR'. The list is
 *     configuration (app.global.gold-loan-seized-statuses), not code, because it changes
 *     with collections policy.
 *
 * Two defects in the legacy version are not carried over: `addrefid != ""` compared
 * String references rather than values, so the address branch was effectively random;
 * and a Double.parseDouble on every amount accumulated binary rounding error into money.
 * This uses BigDecimal and equals() throughout.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgreementMapper {

    /** Charge codes this platform does not collect, so they come out of the totals. */
    private static final Set<String> VISIT_CHARGE_CODES = Set.of("CCVCHGCD", "CCVCHG");

    private static final String STATUS_LOSS = "L";
    private static final String STATUS_CURRENT = "C";
    private static final String STATUS_LOSS_AMOUNT = "LOSSAMT";
    private static final String STATUS_SEIZED = "SR";

    private final FetchProperties props;

    /**
     * @param successArray the LMS response.success[] array
     * @param requestedAgreement used when the LMS returns no account number of its own
     */
    public Mapped map(JsonNode successArray, String requestedAgreement) {
        JsonNode first = successArray.get(0);

        BigDecimal currentDueTotal = BigDecimal.ZERO;
        BigDecimal lossAmountTotal = BigDecimal.ZERO;
        BigDecimal penalOverdueTotal = BigDecimal.ZERO;
        BigDecimal otherOverdueTotal = BigDecimal.ZERO;
        BigDecimal visitChargeTotal = BigDecimal.ZERO;

        String aggregateStatus = STATUS_CURRENT;
        List<FetchDtos.ChargeDue> charges = new ArrayList<>();

        for (JsonNode entry : successArray) {
            String entryStatus = text(entry, "accountStatus");
            if (STATUS_LOSS.equals(entryStatus)) {
                aggregateStatus = STATUS_LOSS;
            }

            currentDueTotal = currentDueTotal.add(amount(entry, "currentdue"));
            penalOverdueTotal = penalOverdueTotal.add(amount(entry, "penalOverdue"));
            otherOverdueTotal = otherOverdueTotal.add(amount(entry, "otherOverdue"));

            // Only from accounts NOT already written off, matching the legacy condition.
            if (!STATUS_LOSS.equals(entryStatus)) {
                lossAmountTotal = lossAmountTotal.add(amount(entry, "lossamount"));
            }

            visitChargeTotal = visitChargeTotal.add(collectCharges(entry, charges));
        }

        BigDecimal currentDue;
        String accountStatus;

        if (lossAmountTotal.signum() > 0 && STATUS_CURRENT.equals(aggregateStatus)) {
            accountStatus = STATUS_LOSS_AMOUNT;
            // Replaces, does not add. The loss amount IS what is collectable.
            currentDue = lossAmountTotal;
        } else {
            accountStatus = aggregateStatus;
            currentDue = currentDueTotal.subtract(visitChargeTotal);
        }

        if (isSeized(text(first, "agreementSubStatus"))) {
            accountStatus = STATUS_SEIZED;
        }

        FetchDtos.Dues dues = FetchDtos.Dues.builder()
                .currentDue(nonNegative(currentDue))
                .penalOverdue(nonNegative(penalOverdueTotal))
                .otherOverdue(nonNegative(otherOverdueTotal.subtract(visitChargeTotal)))
                .principalOverdue(amount(first, "principalOverdue"))
                .interestOverdue(amount(first, "interestOverdue"))
                .bounceChargesOverdue(amount(first, "bounceChargesOverDue"))
                .totalOutstanding(amount(first, "totalOutstanding"))
                .totalPrincipalOutstanding(amount(first, "totalPrincipalOutstanding"))
                .arrears(amount(first, "arrears"))
                .excess(amount(first, "excess"))
                .perDayInterest(amount(first, "perdayInt"))
                .loanAmount(amount(first, "lnamt"))
                .sanctionAmount(amount(first, "sanctionamt"))
                .dpdDays(integer(first, "dpdDays"))
                .charges(charges)
                .agreementCount(successArray.size())
                .build();

        String accountNumber = text(first, "accountnumber");
        String mobile = text(first, "mobilenumber");

        FetchDtos.Customer customer = FetchDtos.Customer.builder()
                .agreementNumber(accountNumber == null || accountNumber.isBlank()
                        ? requestedAgreement : accountNumber)
                .customerName(text(first, "customername"))
                // Masked, always. The full number has no business leaving this service.
                .maskedMobile(mobile == null ? null : Mask.mobile(mobile))
                .accountStatus(accountStatus)
                .openingDate(text(first, "openingDate"))
                .expiryDate(text(first, "expiredt"))
                .nextDueDate(text(first, "nextDueDate"))
                .lastPaymentDate(text(first, "lastPaymentDate"))
                .address(address(first))
                .build();

        return new Mapped(customer, dues);
    }

    /**
     * Accumulates the charge breakdown and returns this entry's visit charge.
     *
     * The legacy code broke out of the charge loop after the first visit charge it found,
     * so a second one on the same account was never subtracted. That looks like a bug
     * rather than a rule, but changing it changes what a borrower is charged — so the
     * behaviour is preserved and flagged. Confirm with the business before removing the
     * break; if visit charges can repeat per account, the legacy totals were wrong too.
     */
    private BigDecimal collectCharges(JsonNode entry, List<FetchDtos.ChargeDue> into) {
        JsonNode chargeArray = entry.path("chargeDueDetails");
        if (!chargeArray.isArray()) {
            return BigDecimal.ZERO;
        }

        BigDecimal visitCharge = BigDecimal.ZERO;
        boolean visitChargeTaken = false;

        for (JsonNode charge : chargeArray) {
            String code = text(charge, "chargeCode");
            BigDecimal due = amount(charge, "chargeDue");

            if (code != null && VISIT_CHARGE_CODES.contains(code) && !visitChargeTaken) {
                visitCharge = due;
                visitChargeTaken = true;   // legacy `break` on the first match
                continue;                  // not shown to the caller: we do not collect it
            }

            if (due.signum() > 0) {
                into.add(FetchDtos.ChargeDue.builder()
                        .chargeCode(code)
                        .chargeName(text(charge, "chargeName"))
                        .chargeDue(due)
                        .build());
            }
        }
        return visitCharge;
    }

    /**
     * Picks the address to show.
     *
     * The LMS returns addressdetails[] plus an optional addressverify[].addrefid naming
     * the verified one. Preference: the verified address, then one typed "Residence
     * Address", then the first entry. The legacy code compared addrefid with != "",
     * which compares references and not values, so in practice it fell through
     * unpredictably.
     */
    private FetchDtos.Address address(JsonNode entry) {
        JsonNode addresses = entry.path("addressdetails");
        if (!addresses.isArray() || addresses.isEmpty()) {
            return null;
        }

        String verifiedRef = null;
        JsonNode verify = entry.path("addressverify");
        if (verify.isArray() && !verify.isEmpty()) {
            verifiedRef = text(verify.get(0), "addrefid");
        }

        JsonNode chosen = null;
        if (verifiedRef != null && !verifiedRef.isBlank()) {
            for (JsonNode candidate : addresses) {
                if (verifiedRef.equals(text(candidate, "addrefid"))) {
                    chosen = candidate;
                    break;
                }
            }
        }
        if (chosen == null) {
            for (JsonNode candidate : addresses) {
                if ("Residence Address".equalsIgnoreCase(text(candidate, "addressType"))) {
                    chosen = candidate;
                    break;
                }
            }
        }
        if (chosen == null) {
            chosen = addresses.get(0);
        }

        String line1 = text(chosen, "addressLineOne");
        if (line1 == null || line1.isBlank()) {
            line1 = text(entry, "address1");
        }

        return FetchDtos.Address.builder()
                .addressType(text(chosen, "addressType"))
                .line1(line1)
                .line2(text(chosen, "addressLineTwo"))
                .landmark(text(chosen, "landmark"))
                .city(text(chosen, "city"))
                .taluk(text(chosen, "taluk"))
                .town(text(chosen, "town"))
                .state(text(chosen, "state"))
                .pincode(text(entry, "pincode"))
                .build();
    }

    private boolean isSeized(String subStatus) {
        if (subStatus == null || subStatus.isBlank()) {
            return false;
        }
        return props.getSeizedSubStatuses().contains(subStatus.trim());
    }

    /**
     * A negative total means the LMS and this arithmetic disagree — most likely a visit
     * charge larger than the due it was subtracted from. Zero is the safe answer: never
     * show a borrower a negative amount payable, and never let one reach the vendor.
     */
    private BigDecimal nonNegative(BigDecimal value) {
        if (value.signum() < 0) {
            log.warn("Computed a negative due ({}); clamping to zero", value);
            return BigDecimal.ZERO;
        }
        return value;
    }

    /** BigDecimal, never double: binary floating point accumulates error into money. */
    private BigDecimal amount(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            String raw = value.isNumber() ? value.asString() : value.asString().trim();
            return raw == null || raw.isBlank() ? BigDecimal.ZERO : new BigDecimal(raw);
        } catch (NumberFormatException e) {
            log.warn("Field {} was not a number: {}", field, value.asString());
            return BigDecimal.ZERO;
        }
    }

    private Integer integer(JsonNode node, String field) {
        BigDecimal value = amount(node, field);
        return value.intValue();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String asText = value.asString();
        return asText == null || asText.isBlank() ? null : asText;
    }

    public record Mapped(FetchDtos.Customer customer, FetchDtos.Dues dues) {}
}
