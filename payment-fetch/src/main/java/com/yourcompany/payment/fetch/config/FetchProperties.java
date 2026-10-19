package com.yourcompany.payment.fetch.config;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Settings only fetch-service needs. */
@Data
@Validated
@ConfigurationProperties(prefix = "fetch")
public class FetchProperties {

    /**
     * Charge types a caller may ask for. An unrecognised value is rejected.
     *
     * Deliberately NOT an enum in code: adding a charge type should not need a release,
     * and the list differs between UAT and production while the business settles it.
     */
    @NotEmpty
    private Set<String> allowedChargeTypes = new LinkedHashSet<>();

    /**
     * chargeType -> which figure from the LMS payload is payable.
     *
     * SEAM. The mapping was never supplied. Accepted values are the Dues field names:
     * CURRENT_DUE, PENAL_OVERDUE, OTHER_OVERDUE, TOTAL_OUTSTANDING, ARREARS.
     *
     * A charge type with no mapping returns payableAmount = null and the components for
     * the caller to work from. That is on purpose: a default here would be a guess about
     * how much money to take from a borrower, and the most likely wrong guess —
     * TOTAL_OUTSTANDING — is also the largest.
     */
    private Map<String, String> chargeTypeAmount = new LinkedHashMap<>();

    /**
     * Agreement sub-statuses that mean seized or repossessed, from the legacy
     * GOLDLOANSEIZEDCASE value. Configuration rather than code because it changes with
     * collections policy.
     */
    private Set<String> seizedSubStatuses = new LinkedHashSet<>();
}
