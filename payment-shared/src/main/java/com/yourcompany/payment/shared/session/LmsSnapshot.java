package com.yourcompany.payment.shared.session;

import com.yourcompany.payment.shared.dto.LoanDetails;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * The LMS agreement-fetch response, captured once per session.
 *
 * Written by auth-service when it looks up the mobile number for OTP dispatch, then read
 * by fetch-service instead of calling the LMS again. One call saved per journey, and the
 * borrower sees the same data the OTP was sent against.
 *
 * SCOPE: one session. Not one agreement. Two sessions for the same borrower hold separate
 * snapshots and may legitimately disagree — each shows what was true when it started. An
 * agreement-keyed cache would share state between unrelated sessions and make
 * invalidation impossible.
 *
 * WHAT THIS IS SAFE FOR: display. Name, mobile and account status do not change inside a
 * five-minute session.
 *
 * WHAT IT IS NOT SAFE FOR: deciding how much to charge. If the borrower pays through
 * another channel between OTP and the payment screen, the dues here are stale. Payment
 * initiation must validate the amount against a fresh LMS call, not against this.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LmsSnapshot {

    /** The mapped subset the authentication flow uses. */
    private LoanDetails details;

    /**
     * The raw success[0] object from the LMS response, so fetch-service can map dues,
     * charges and address fields without a second call. Stored as JSON text rather than a
     * modelled object, because those fields belong to fetch-service's contract, not this
     * library's.
     */
    private String rawAgreementJson;

    private Instant capturedAt;
}
