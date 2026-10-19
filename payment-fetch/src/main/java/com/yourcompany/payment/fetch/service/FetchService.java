package com.yourcompany.payment.fetch.service;

import com.yourcompany.payment.fetch.dto.FetchDtos;
import com.yourcompany.payment.shared.client.LmsClient;
import com.yourcompany.payment.shared.dto.LoanDetails;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.exception.SessionException;
import com.yourcompany.payment.shared.security.JwtService;
import com.yourcompany.payment.shared.security.SessionContext;
import com.yourcompany.payment.shared.security.TokenClaims;
import com.yourcompany.payment.shared.session.LmsSnapshot;
import com.yourcompany.payment.shared.session.LmsSnapshotStore;
import com.yourcompany.payment.shared.util.Mask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The one piece of business logic in this service, reached by two front doors.
 *
 * Both callers get the same validation, the same dues arithmetic, the same vendor check
 * and the same response shape. What differs is exactly two things, and keeping them to
 * two is the point of this class:
 *
 * <pre>
 *                      BORROWER (browser)              PARTNER (machine)
 *   authenticated by   session cookie + header,        bearer JWT, aud=user,
 *                      borrower JWT bound to the       role VENDOR or ADMIN
 *                      session AND the agreement
 *   agreement comes    from the token binding          from the request body
 *   data comes from    the session snapshot, with      always a fresh LMS call
 *                      the LMS as fallback
 * </pre>
 *
 * <h2>Why the borrower path reads a snapshot and the partner path does not</h2>
 * auth-service already called the LMS for this agreement to find the mobile number for
 * OTP dispatch, and stored the response against the session. Reading it saves one LMS
 * call per journey, which matters because the LMS is the platform's ceiling. A partner
 * has no session and no prior call, so there is nothing to read.
 *
 * The snapshot is safe for display and not for charging — a borrower may have paid
 * elsewhere in the intervening minutes. {@code fromSessionSnapshot} is on the response
 * so the caller knows which it got, and payment initiation must re-check the amount
 * against a fresh LMS call regardless.
 *
 * <h2>Where the borrower's token is checked</h2>
 * The filter answers one question — is this token valid for this session — and sets
 * ROLE_BORROWER so the chain can require it. Signature, algorithm, issuer, audience,
 * expiry, {@code sub} == session. That is all it can do: the agreement lives inside the
 * AES-GCM body and a servlet filter runs before the decryption advice.
 *
 * The service then matches the session's agreement against the token's. Both are
 * server-side values written by auth-service at OTP time, so the borrower cannot
 * influence either, and the body's agreement number selects nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FetchService {

    private final LmsClient lmsClient;
    private final LmsSnapshotStore snapshots;
    private final JwtService jwtService;
    private final AgreementMapper mapper;
    private final ChargeTypeResolver chargeTypes;
    private final VendorValidator vendorValidator;
    private final ObjectMapper objectMapper;

    // ============================================================ borrower path

    /**
     * @param session resolved by SessionContextFilter from the cookie/header pair
     * @param claims  already verified by BorrowerAuthFilter: signature, issuer, audience,
     *                expiry, and the binding to this session. Nothing else.
     */
    public FetchDtos.FetchResponse fetchForBorrower(FetchDtos.FetchRequest request,
                                                    SessionContext session,
                                                    TokenClaims claims) {

        Optional<LmsSnapshot> snapshot = snapshots.get(session.sessionId());

        // THE AGREEMENT COMES FROM THE SESSION, not from the request. See
        // resolveAgreement: the body field is a fallback for a missing snapshot and
        // nothing more, and the token has to agree with whichever source was used.
        String agreementNumber = resolveAgreement(session, claims, snapshot,
                request.getAgreementNumber());

        String chargeType = chargeTypes.validate(request.getChargeType());

        AgreementMapper.Mapped mapped;
        boolean fromSnapshot;

        if (snapshot.isPresent() && snapshot.get().getRawAgreementJson() != null) {
            mapped = mapRaw(snapshot.get().getRawAgreementJson(), agreementNumber);
            fromSnapshot = true;
            // Keep the snapshot alive alongside the session, so a borrower who pauses on
            // the payment screen does not lose it and trigger a second LMS call.
            snapshots.touch(session.sessionId());
        } else {
            log.info("No session snapshot for {}; calling the LMS", Mask.agreement(agreementNumber));
            mapped = fromLms(agreementNumber);
            fromSnapshot = false;
        }

        return assemble(mapped, chargeType, request.getPaymentVendor(), fromSnapshot);
    }

    /**
     * Decides which agreement this request is about, for a borrower.
     *
     * <h3>The session is the source, not the request</h3>
     * auth-service looked the agreement up to send the OTP and stored it against the
     * session. That value is server-side and the borrower cannot influence it, so a
     * borrower gets their own agreement whatever they put in the body. The body field
     * therefore selects nothing — there is no object reference for a caller to tamper
     * with.
     *
     * A body value that disagrees with the session is logged and ignored rather than
     * rejected. The usual cause is a stale tab, not an attack, and the request is
     * answered correctly either way.
     *
     * <h3>Why the body is still a fallback</h3>
     * The snapshot can be gone — Redis restarted, or the key expired just before this
     * request. With no session agreement there is nothing to work from, and failing
     * would send the borrower back to the start for a cache miss. So the body value is
     * accepted in that one case.
     *
     * <h3>The check that makes both sources safe</h3>
     * {@code requireAgreement} runs on the RESOLVED value, whichever source produced it.
     * The token's keyed agreement hash was written by auth-service at OTP verification,
     * so the only value that passes is the one this borrower actually verified against.
     * The fallback path cannot therefore be used to read another loan: supply a different
     * agreement and the hash will not match.
     */
    private String resolveAgreement(SessionContext session,
                                    TokenClaims claims,
                                    Optional<LmsSnapshot> snapshot,
                                    String fromBody) {

        String fromSession = snapshot
                .map(LmsSnapshot::getDetails)
                .map(LoanDetails::getAgreementNumber)
                .filter(a -> a != null && !a.isBlank())
                .map(this::normalise)
                .orElse(null);

        String resolved;
        if (fromSession != null) {
            resolved = fromSession;
            String presented = normalise(fromBody);
            if (!presented.isEmpty() && !presented.equals(fromSession)) {
                log.warn("Request named a different agreement than the session for {}; using the session",
                        Mask.sessionTail(session.sessionId()));
            }
        } else {
            resolved = normalise(fromBody);
            if (resolved.isEmpty()) {
                throw SessionException.notFound();
            }
            log.info("No session agreement for {}; falling back to the request value",
                    Mask.sessionTail(session.sessionId()));
        }

        // On the resolved value, every time, from either source.
        jwtService.requireAgreement(claims, resolved);
        return resolved;
    }

    // ============================================================= partner path

    /**
     * No session, no snapshot, no encryption — and therefore always a fresh LMS call.
     *
     * The agreement number comes from the request because a partner token is bound to no
     * agreement. That is a deliberate difference in the trust model, not an oversight:
     * a partner is authorised to act for many borrowers, so the thing that limits them is
     * their role and the LMS's own answer, not a token binding.
     */
    public FetchDtos.FetchResponse fetchForPartner(FetchDtos.FetchRequest request,
                                                   TokenClaims claims) {
        // No agreement binding, and no role check here: a partner token carries no
        // agreement, and the role is matched by the filter chain
        // (hasAnyAuthority(VENDOR, ADMIN) in SecurityConfig) so that a path is
        // unreachable without it rather than relying on a line in a service.
        String agreementNumber = normalise(request.getAgreementNumber());
        String chargeType = chargeTypes.validate(request.getChargeType());

        log.info("Partner fetch user={} agreement={}", claims.subject(), Mask.agreement(agreementNumber));

        AgreementMapper.Mapped mapped = fromLms(agreementNumber);
        return assemble(mapped, chargeType, request.getPaymentVendor(), false);
    }

    // ================================================================== shared

    /**
     * Order matters here. The amount is resolved before the vendor is checked, because
     * the vendor's limits are checked against that amount. Validating the vendor first
     * would pass a vendor that cannot accept the sum involved.
     */
    private FetchDtos.FetchResponse assemble(AgreementMapper.Mapped mapped,
                                             String chargeType,
                                             String vendorCode,
                                             boolean fromSnapshot) {
        BigDecimal payable = chargeTypes.payableAmount(chargeType, mapped.dues());
        mapped.dues().setPayableAmount(payable);

        FetchDtos.Vendor vendor = vendorValidator.validate(vendorCode, payable);

        return FetchDtos.FetchResponse.builder()
                .customer(mapped.customer())
                .dues(mapped.dues())
                .vendor(vendor)
                .chargeType(chargeType)
                .fromSessionSnapshot(fromSnapshot)
                .build();
    }

    private AgreementMapper.Mapped fromLms(String agreementNumber) {
        LmsClient.AgreementLookup lookup = lmsClient.lookupWithPayload(agreementNumber)
                .orElseThrow(() -> {
                    // Deliberately the same answer for "no such agreement" and "the
                    // lookup failed". A caller must not be able to probe which
                    // agreement numbers exist.
                    log.info("LMS returned nothing for {}", Mask.agreement(agreementNumber));
                    return new ApiException("AGREEMENT_NOT_FOUND",
                            "We could not find those loan details.", HttpStatus.NOT_FOUND);
                });

        return mapRaw(lookup.rawAgreementJson(), agreementNumber);
    }

    /**
     * The snapshot stores success[0] as a single object; a fresh lookup hands back the
     * same thing. The mapper sums across entries, so a lone object is wrapped into a
     * one-element array rather than given a second code path.
     *
     * A multi-account customer read from a snapshot therefore shows only the first
     * account's figures, because that is all auth-service kept. It is recorded here
     * rather than hidden: if multi-account gold-loan customers are common, auth-service
     * should store the whole array instead.
     */
    private AgreementMapper.Mapped mapRaw(String rawJson, String agreementNumber) {
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            JsonNode asArray = node.isArray() ? node
                    : objectMapper.createArrayNode().add(node);

            if (asArray.isEmpty()) {
                throw new ApiException("AGREEMENT_NOT_FOUND",
                        "We could not find those loan details.", HttpStatus.NOT_FOUND);
            }
            return mapper.map(asArray, agreementNumber);

        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // A stored payload that will not parse is a bug on our side, not the
            // caller's. It must not be reported as "agreement not found", or nobody ever
            // finds out.
            log.error("Could not parse the LMS payload for {}: {}",
                    Mask.agreement(agreementNumber), e.getClass().getSimpleName());
            throw new ApiException("INTERNAL_ERROR", "Unable to process request",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String normalise(String agreementNumber) {
        return agreementNumber == null ? "" : agreementNumber.trim().toUpperCase();
    }
}
