package com.yourcompany.payment.shared.client;

import com.yourcompany.payment.shared.dto.LoanDetails;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Every LMS API this platform calls, in one contract.
 *
 * One interface and one implementation, deliberately. The alternative - a class per
 * endpoint - multiplies by the number of APIs and duplicates the connection, header,
 * timing and logging code in each one. {@link LmsApiClient} implements all of these on
 * top of {@link UpstreamHttpClient}, so the next endpoint is one method, not one class.
 *
 * Empty Optionals mean "we could not verify these details", covering both "no such
 * agreement" and "lookup failed" so the caller cannot tell them apart - and therefore
 * neither can the borrower's browser.
 */
public interface LmsClient {

    // ---------------------------------------------------------------- auth phase

    Optional<LoanDetails> findByAgreementNumber(String agreementNumber);

    /**
     * Same lookup, also returning the raw success[0] JSON.
     *
     * auth-service uses this so the response can be stored as a session snapshot and
     * fetch-service can map dues and address fields from it without a second LMS call.
     */
    Optional<AgreementLookup> lookupWithPayload(String agreementNumber);

    record AgreementLookup(LoanDetails details, String rawAgreementJson) {}

    // ------------------------------------------------------------- posting phase

    /**
     * Dues and charge breakdown that decide which rule applies to this payment.
     *
     * Called again on every re-post attempt, never cached across attempts: the borrower
     * may have paid elsewhere in between and the applicable rule changes with the
     * outstanding figures.
     */
    JsonNode otsAmount(String agreementNumber, String txnReference, Integer attemptNo);

    /**
     * Has this transaction already reached the LMS?
     *
     * Keyed on our own transaction reference. Called before every re-post, and by the
     * reconciliation sweep, so a slow first attempt that actually landed is never
     * posted twice.
     */
    JsonNode statusCheck(String txnReference, Integer attemptNo);

    /** Posts the repayment. The last call in the posting chain. */
    JsonNode postTransaction(PostingRequest request);

    /**
     * What the posting endpoint needs from us.
     *
     * {@code attemptNo} is carried so the audit row says which try this was, and
     * {@code txnReference} is the challan number generated on our side - the key every
     * status check and reconciliation run uses.
     */
    record PostingRequest(String agreementNumber, String txnReference, Integer attemptNo,
                          BigDecimal amount, String paymentMode, String paymentDate,
                          String vendorReference) {}
}
